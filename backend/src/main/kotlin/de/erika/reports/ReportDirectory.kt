package de.erika.reports

import de.erika.sync.SyncRejection
import de.erika.sync.SyncViewer
import de.erika.workorders.WorkOrderDirectory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Published API of the `reports` module for records that belong to a report but live in other
 * modules (MARA Stage 6: photos in `media`). Runs in the caller's tenant transaction.
 */
@Service
class ReportDirectory internal constructor(
    private val repo: ReportsRepository,
    private val guard: ReportGuard,
    private val workOrders: WorkOrderDirectory,
) {
    /** The reports among [reportIds] that [viewer] has on the phone (where their work order is). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun visibleTo(
        reportIds: Collection<UUID>,
        viewer: SyncViewer,
    ): Set<UUID> {
        val reports = repo.reports(reportIds.distinct())
        val visible = workOrders.visibleTo(reports.map { it.workOrderId }, viewer)
        return reports.filter { it.workOrderId in visible }.map { it.id }.toSet()
    }

    /**
     * Throws [SyncRejection] unless [viewer] may add to or change [reportId]: `REPORT_NOT_FOUND`,
     * `NOT_IN_SCOPE` or `REPORT_LOCKED` (signed or invoiced).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun checkWritable(
        reportId: UUID,
        viewer: SyncViewer,
    ) {
        guard.writable(reportId, viewer)
    }

    /** The checklist item keys of [reportId]'s work order (empty without a template). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun checklistKeys(reportId: UUID): Set<String> {
        val report = repo.reports(listOf(reportId)).firstOrNull() ?: return emptySet()
        val order = workOrders.find(report.workOrderId) ?: return emptySet()
        return order.checklistTemplateId
            ?.let { workOrders.checklistItems(it) }
            .orEmpty()
            .map { it.key }
            .toSet()
    }

    /** The IDs of all versions of a work order's report (to resend their records on reassignment). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun reportIdsOf(workOrderId: UUID): List<UUID> = repo.reportIdsOf(workOrderId)

    /**
     * Signed (or unsigned-sent) versions for invoicing (MARA Stage 8): times, materials and the
     * archived PDF. Only versions that are locked and the newest of their work order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun billingData(reportIds: Collection<UUID>): Map<UUID, ReportBilling> {
        val reports = repo.reports(reportIds.distinct()).filter { it.state.locked }
        val latest = reports.map { it.workOrderId }.distinct().associateWith { repo.reportOf(it)?.id }
        val orders = workOrders.findAll(reports.map { it.workOrderId })
        return reports
            .filter { latest[it.workOrderId] == it.id }
            .mapNotNull { r ->
                val signature = repo.signatureOf(r.id) ?: return@mapNotNull null
                val order = orders[r.workOrderId] ?: return@mapNotNull null
                r.id to
                    ReportBilling(
                        reportId = r.id,
                        workOrderId = order.id,
                        workOrderTitle = order.title,
                        customerId = order.customerId,
                        version = r.version,
                        state = r.state.name,
                        signedAt = signature.signedAt,
                        verificationCode = signature.verificationCode,
                        pdfSha256 = signature.pdfSha256,
                        times = repo.timeEntriesOf(r.id).map { ReportTime(it.technicianId, it.kind, it.startedAt, it.endedAt) },
                        materials = repo.materialsOf(r.id).map { ReportMaterial(it.articleId, it.description, it.quantity, it.unitCode) },
                    )
            }.toMap()
    }

    /** The newest locked version of every work order's report (candidates for invoicing). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockedLatest(): List<UUID> = repo.lockedLatest()

    /** The current report of a work order, if one was started. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun currentReportOf(workOrderId: UUID): UUID? = repo.reportOf(workOrderId)?.id
}

/** A signed report version as invoicing sees it. */
data class ReportBilling(
    val reportId: UUID,
    val workOrderId: UUID,
    val workOrderTitle: String,
    val customerId: UUID,
    val version: Int,
    val state: String,
    val signedAt: java.time.OffsetDateTime,
    val verificationCode: String,
    /** The archived PDF; null until the worker made it. */
    val pdfSha256: String?,
    val times: List<ReportTime>,
    val materials: List<ReportMaterial>,
)

data class ReportTime(
    val technicianId: UUID,
    val kind: String,
    val startedAt: java.time.OffsetDateTime,
    val endedAt: java.time.OffsetDateTime?,
)

data class ReportMaterial(
    val articleId: UUID?,
    val description: String,
    val quantity: java.math.BigDecimal,
    val unitCode: String,
)

/** A photo of a report, as the office report lists it. */
data class ReportPhotoInfo(
    val id: UUID,
    val caption: String?,
    val category: String,
    val checklistItemKey: String?,
    val state: String,
    val rejectReason: String?,
    val takenAt: java.time.OffsetDateTime,
    val authorId: UUID,
    val width: Int?,
    val height: Int?,
    val sha256: String,
)

/**
 * A report's media kept outside `reports` (MARA Stage 6: photos in `media`, which implements
 * this). A mandatory PHOTO checklist item counts as answered when a photo for it exists.
 */
interface ReportMedia {
    /** Keys of the checklist items of [reportId] that have at least one photo (not rejected). */
    fun answeredItems(reportId: UUID): Set<String>

    /** The report's photos (not deleted), oldest first. */
    fun photos(reportId: UUID): List<ReportPhotoInfo>

    /** Copies the photos of [from] into the correction [to] (they share the files, Stage 7). */
    fun copyPhotos(
        from: UUID,
        to: UUID,
        workspaceId: UUID,
        actorId: UUID,
    )

    /** A STORED photo's image (the thumbnail when [thumbnail]), or null. */
    fun image(
        photoId: UUID,
        thumbnail: Boolean,
    ): ByteArray?

    /** Stores the STORED photos of [reportId] write-once in the archive until [retainUntil] (ADR 0040). */
    fun archive(
        reportId: UUID,
        workspaceId: UUID,
        retainUntil: java.time.LocalDate,
    )
}
