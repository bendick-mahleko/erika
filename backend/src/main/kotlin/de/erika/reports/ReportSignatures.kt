package de.erika.reports

import de.erika.creator.CustomerDirectory
import de.erika.identity.WorkspaceContacts
import de.erika.platform.jobs.JobQueue
import de.erika.sync.FieldPolicy
import de.erika.sync.SyncEntity
import de.erika.sync.SyncRejection
import de.erika.sync.SyncViewer
import de.erika.workorders.ChecklistItemKind
import de.erika.workorders.WorkOrderDirectory
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/*
 * MARA Stage 7: signatures (M-MO-09, M-MO-10, M-BR-01 to M-BR-03). The phone shows the customer
 * the canonical report, the customer signs, and the phone sends a signature record with the
 * SHA-256 of what was shown. The server rebuilds the canonical report from its own data and
 * accepts the signature only if the hash is the same; then the version is locked.
 */

/** The canonical report as the server sees it (the input of [ReportCanonical]). */
@Component
internal class ReportSnapshots(
    private val repo: ReportsRepository,
    private val workOrders: WorkOrderDirectory,
    private val customers: CustomerDirectory,
    private val people: WorkspaceContacts,
    private val media: ObjectProvider<ReportMedia>,
) {
    fun raw(report: Report): Map<String, Any?> {
        val order = workOrders.find(report.workOrderId) ?: error("work order of report ${report.id} is missing")
        val times = repo.timeEntriesOf(report.id)
        val names = people.displayNames(times.map { it.technicianId })
        val items = order.checklistTemplateId?.let { workOrders.checklistItems(it) }.orEmpty()
        val answers = repo.answersOf(report.id).associateBy { it.itemKey }
        return mapOf(
            "format" to ReportCanonical.FORMAT,
            "reportId" to report.id.toString(),
            "version" to report.version,
            "workOrder" to mapOf("id" to order.id.toString(), "title" to order.title),
            "customer" to mapOf("name" to customers.find(order.customerId)?.name),
            "workDescription" to report.workDescription,
            "times" to
                times.map {
                    mapOf(
                        "id" to it.id.toString(),
                        "technician" to names[it.technicianId],
                        "kind" to it.kind,
                        "start" to it.startedAt.toInstant().toString(),
                        "end" to it.endedAt?.toInstant()?.toString(),
                        "note" to it.note,
                    )
                },
            "materials" to
                repo.materialsOf(report.id).map {
                    mapOf("id" to it.id.toString(), "description" to it.description, "quantity" to it.quantity, "unit" to it.unitCode)
                },
            "aufmass" to
                repo.aufmassOf(report.id).map { p ->
                    mapOf(
                        "id" to p.id.toString(),
                        "label" to p.label,
                        "kind" to p.kind,
                        "length" to p.length,
                        "width" to p.width,
                        "height" to p.height,
                        "count" to p.count,
                        "expression" to p.expression,
                        "deductions" to
                            p.deductions.map {
                                mapOf(
                                    "label" to it.label,
                                    "length" to it.length,
                                    "width" to it.width,
                                    "height" to it.height,
                                    "count" to it.count,
                                )
                            },
                        "result" to p.result,
                        "unit" to p.unitCode,
                    )
                },
            // Photo items are answered by the photos below.
            "checklist" to
                items.filter { it.kind != ChecklistItemKind.PHOTO }.map {
                    mapOf("key" to it.key, "label" to it.label, "value" to answers[it.key]?.value)
                },
            "photos" to
                media.orderedStream().toList().flatMap { it.photos(report.id) }.map {
                    mapOf(
                        "id" to it.id.toString(),
                        "category" to it.category,
                        "caption" to it.caption,
                        "checklistItemKey" to it.checklistItemKey,
                        "sha256" to it.sha256,
                    )
                },
        )
    }
}

/**
 * The signature of a report version, or the reason it is missing (sync entity `report_signature`,
 * create-only). Its ID is derived from the report (`ids/derived.json`), so a second phone signing
 * the same version offline can't make a second one.
 */
@Component
internal class ReportSignatureSyncEntity(
    private val repo: ReportsRepository,
    private val guard: ReportGuard,
    private val snapshots: ReportSnapshots,
    private val workOrders: WorkOrderDirectory,
    private val jobs: ObjectProvider<JobQueue>,
    private val events: ApplicationEventPublisher,
    private val json: JsonMapper,
    meters: MeterRegistry,
) : SyncEntity {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mismatches = meters.counter("erika.mara.signature.hash.mismatch")

    override val name = NAME

    override val fields =
        listOf(
            "reportId",
            "kind",
            "contentHash",
            "signerName",
            "signedAt",
            "comment",
            "notSignedReason",
            "strokes",
            "signaturePng",
            "verificationCode",
            "downloadKey",
            "customerEmail",
        ).associateWith { FieldPolicy.SERVER_WINS }

    override fun allIds(): List<UUID> = repo.allIds("report_signature")

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        repo.signatures(ids).associate { s ->
            s.id to
                linkedMapOf(
                    "reportId" to s.reportId.toString(),
                    "kind" to s.kind,
                    "contentHash" to s.contentHash,
                    "signerName" to s.signerName,
                    "signedAt" to s.signedAt.toInstant().toString(),
                    "comment" to s.comment,
                    "notSignedReason" to s.notSignedReason,
                    "signaturePng" to s.signaturePng?.let { Base64.getEncoder().encodeToString(it) },
                    "verificationCode" to s.verificationCode,
                    "customerEmail" to s.customerEmail,
                    "createdBy" to s.createdBy.toString(),
                    "pdfArchived" to (s.pdfSha256 != null),
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ) = guard.viewChildren(mapOf(UUID(0, 0) to data), viewer).values.first()

    override fun viewAll(
        data: Map<UUID, Map<String, Any?>>,
        viewer: SyncViewer,
    ) = guard.viewChildren(data, viewer)

    /** Written once: a second signature for the same version changes nothing. */
    override fun mayWrite(
        field: String,
        viewer: SyncViewer,
    ) = false

    override fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val reportId = (values["reportId"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw invalid("reportId")
        if (id != DerivedIds.of(reportId, "signature")) throw SyncRejection("INVALID_SIGNATURE_ID")
        val (report, order) = guard.writable(reportId, viewer)
        val kind = text(values, "kind", 20)?.takeIf { it == "SIGNED" || it == "NOT_SIGNED" } ?: throw invalid("kind")
        guard.checkCompletable(report, order)

        val claimed = text(values, "contentHash", 64)?.lowercase() ?: throw invalid("contentHash")
        if (claimed != ReportCanonical.hash(snapshots.raw(report))) {
            // What the customer saw isn't what the server holds (support is alerted: ErikaMaraSignatureHashMismatch).
            mismatches.increment()
            log.error("Signature for report {} refused: the content hash differs from the server's", reportId)
            throw SyncRejection("HASH_MISMATCH")
        }
        val code = text(values, "verificationCode", 10)?.takeIf { CODE.matches(it) } ?: throw invalid("verificationCode")
        if (repo.codeTaken(code)) throw SyncRejection("CODE_TAKEN")
        val key = text(values, "downloadKey", 100)?.takeIf { KEY.matches(it) } ?: throw invalid("downloadKey")
        val png = text(values, "signaturePng", MAX_PNG_BASE64)?.let { decodePng(it) }
        val signature =
            ReportSignature(
                id = id,
                reportId = reportId,
                kind = kind,
                contentHash = claimed,
                signerName = text(values, "signerName", 200),
                signedAt =
                    (values["signedAt"] as? String)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                        ?: throw invalid("signedAt"),
                comment = text(values, "comment", 2_000),
                notSignedReason = text(values, "notSignedReason", 2_000),
                strokes = strokes(values["strokes"]),
                signaturePng = png,
                verificationCode = code,
                downloadKeyHash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.US_ASCII)),
                customerEmail = text(values, "customerEmail", 320)?.takeIf { EMAIL.matches(it) },
                createdBy = viewer.userId,
                deviceId = viewer.deviceId,
            )
        if (kind == "SIGNED" && (signature.signerName == null || png == null)) throw SyncRejection("SIGNATURE_INCOMPLETE")
        if (kind == "NOT_SIGNED" && signature.notSignedReason == null) throw SyncRejection("REASON_REQUIRED")

        repo.insertSignature(viewer.workspaceId, signature)
        repo.setFinalState(reportId, if (kind == "SIGNED") ReportState.SIGNED else ReportState.NOT_SIGNED, viewer.userId)
        if (kind == "SIGNED") workOrders.markSigned(viewer.workspaceId, viewer.userId, order.id)
        events.publishEvent(ReportsChanged(viewer.workspaceId, viewer.userId, NAME, id, "created"))
        events.publishEvent(
            ReportsChanged(
                viewer.workspaceId,
                viewer.userId,
                ReportSyncEntity.NAME,
                reportId,
                if (kind ==
                    "SIGNED"
                ) {
                    "signed"
                } else {
                    "sent_unsigned"
                },
            ),
        )
        jobs.ifAvailable?.enqueue(FinalizeReportJob.TYPE, FinalizeReport(reportId), viewer.workspaceId)
    }

    private fun decodePng(base64: String): ByteArray {
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: throw invalid("signaturePng")
        if (bytes.size < 8 || !bytes.copyOf(8).contentEquals(PNG)) throw invalid("signaturePng")
        return bytes
    }

    /** Strokes as JSON: a list of strokes, each a list of [x, y] points (at most 10,000 points). */
    private fun strokes(value: Any?): String? {
        val list = value as? List<*> ?: return null
        val points = list.sumOf { (it as? List<*>)?.size ?: throw invalid("strokes") }
        if (points > 10_000) throw invalid("strokes")
        return json.writeValueAsString(list)
    }

    private fun text(
        values: Map<String, Any?>,
        field: String,
        max: Int,
    ): String? =
        when (val v = values[field]) {
            null -> null
            is String -> v.trim().ifEmpty { null }?.also { if (it.length > max) throw invalid(field) }
            else -> throw invalid(field)
        }

    private fun invalid(field: String) = SyncRejection("INVALID_${field.uppercase()}")

    companion object {
        const val NAME = "report_signature"
        private val CODE = Regex("^[A-Z2-7]{10}$")
        private val KEY = Regex("^[A-Za-z0-9_-]{22,100}$")
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        private const val MAX_PNG_BASE64 = 400_000
    }
}
