package de.erika.reports.web

import de.erika.archive.Archive
import de.erika.contract.api.ReportsApi
import de.erika.contract.model.AufmassDeduction
import de.erika.contract.model.AufmassKind
import de.erika.contract.model.ChecklistItemKind
import de.erika.contract.model.PhotoCategory
import de.erika.contract.model.PhotoState
import de.erika.contract.model.ReportAufmassPosition
import de.erika.contract.model.ReportChecklistItem
import de.erika.contract.model.ReportInvoiceInfo
import de.erika.contract.model.ReportMaterialLine
import de.erika.contract.model.ReportPhoto
import de.erika.contract.model.ReportSignatureInfo
import de.erika.contract.model.ReportState
import de.erika.contract.model.ReportSummary
import de.erika.contract.model.ReportTimeEntry
import de.erika.contract.model.ReportVersion
import de.erika.contract.model.TimeEntryKind
import de.erika.contract.model.WorkOrderReport
import de.erika.creator.ArticleDirectory
import de.erika.creator.SourceInvoices
import de.erika.identity.WorkspaceContacts
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.security.WorkspaceRole
import de.erika.platform.web.ErikaException
import de.erika.reports.ReportMedia
import de.erika.reports.ReportsRepository
import de.erika.workorders.WorkOrderDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * The job report of a work order for the office (api/openapi.yaml, tag `reports`, MARA Stage 5).
 * Read-only for every web role: reports are written in the MARA app through sync.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class ReportsController(
    private val repo: ReportsRepository,
    private val workOrders: WorkOrderDirectory,
    private val people: WorkspaceContacts,
    private val articles: ArticleDirectory,
    private val media: ObjectProvider<ReportMedia>,
    private val archive: Archive,
    private val invoices: SourceInvoices,
) : ReportsApi {
    /** Raw time entries (no rounding) as CSV for payroll, semicolon-separated (M-IV-07). */
    @PreAuthorize("hasRole('OWNER')")
    override fun exportTimeEntries(
        from: java.time.LocalDate,
        to: java.time.LocalDate,
    ): ResponseEntity<Resource> {
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS
                .between(from, to) > 366
        ) {
            throw ErikaException("RANGE_INVALID", HttpStatus.BAD_REQUEST, "Choose a range of at most one year.")
        }
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val times =
            repo.timeEntriesBetween(
                from.atStartOfDay(zone).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
            )
        val reports = repo.reports(times.map { it.reportId }.distinct()).associateBy { it.id }
        val orders = workOrders.findAll(reports.values.map { it.workOrderId }.distinct())
        val names = people.displayNames(times.map { it.technicianId }.distinct())
        val date =
            java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy")
        val clock =
            java.time.format.DateTimeFormatter
                .ofPattern("HH:mm")
        val csv = StringBuilder("\uFEFFDatum;Mitarbeiter;Art;Beginn;Ende;Minuten;Auftrag;Notiz\r\n")
        for (t in times) {
            val start = t.startedAt.atZoneSameInstant(zone)
            val end = t.endedAt?.atZoneSameInstant(zone)
            val order = reports[t.reportId]?.let { orders[it.workOrderId] }
            val row =
                listOf(
                    date.format(start),
                    names[t.technicianId] ?: "",
                    if (t.kind == "TRAVEL") "Fahrt" else "Arbeit",
                    clock.format(start),
                    end?.let(clock::format) ?: "",
                    t.endedAt?.let {
                        java.time.Duration
                            .between(t.startedAt, it)
                            .toMinutes()
                            .toString()
                    } ?: "",
                    order?.title ?: "",
                    t.note ?: "",
                )
            csv.append(row.joinToString(";") { csvCell(it) }).append("\r\n")
        }
        return download(
            csv.toString().toByteArray(StandardCharsets.UTF_8),
            "Zeiten-$from-$to.csv",
            MediaType("text", "csv", StandardCharsets.UTF_8),
        )
    }

    /** The archived PDF of a signed version (hash-checked by the archive), as a download. */
    override fun getReportPdf(reportId: UUID): ResponseEntity<Resource> {
        val signature = repo.signatureOf(reportId) ?: throw notFound()
        val sha = signature.pdfSha256 ?: throw notFound()
        val content = archive.open(ErikaPrincipal.current().workspaceId, sha) ?: throw notFound()
        return pdf(content.bytes, "Arbeitsbericht-${signature.verificationCode}.pdf")
    }

    private fun notFound() = ErikaException("REPORT_PDF_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")

    override fun getWorkOrderReport(workOrderId: UUID): ResponseEntity<WorkOrderReport> {
        val order =
            workOrders.find(workOrderId) ?: throw ErikaException("WORK_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")
        val items = order.checklistTemplateId?.let { workOrders.checklistItems(it) }.orEmpty()
        val report = repo.reportOf(order.id)
        if (report == null) {
            val checklist =
                items.map {
                    ReportChecklistItem(
                        key = it.key,
                        label = it.label,
                        kind = ChecklistItemKind.forValue(it.kind.name),
                        required = it.required,
                    )
                }
            return ResponseEntity.ok(
                WorkOrderReport(
                    workOrderId = order.id,
                    timeEntries = emptyList(),
                    materials = emptyList(),
                    aufmass = emptyList(),
                    checklist = checklist,
                    photos = emptyList(),
                    versions = emptyList(),
                ),
            )
        }
        // M-NF-09: employees' raw time records are for owners and the office, not for tax advisors
        // (the signed report PDF, a document the customer gets, still shows the job's times).
        val times = if (ErikaPrincipal.current().role == WorkspaceRole.ADVISOR) emptyList() else repo.timeEntriesOf(report.id)
        val materials = repo.materialsOf(report.id)
        val answers = repo.answersOf(report.id).associateBy { it.itemKey }
        val photos = media.orderedStream().toList().flatMap { it.photos(report.id) }
        val names = people.displayNames(times.map { it.technicianId } + photos.map { it.authorId } + listOfNotNull(report.completedBy))
        val articleRefs = articles.findAll(materials.mapNotNull { it.articleId })
        return ResponseEntity.ok(
            WorkOrderReport(
                workOrderId = order.id,
                report =
                    ReportSummary(
                        id = report.id,
                        version = report.version,
                        state = ReportState.forValue(report.state.name),
                        workDescription = report.workDescription,
                        completedAt = report.completedAt,
                        completedByName = report.completedBy?.let { names[it] },
                        signature =
                            repo.signatureOf(report.id)?.let { s ->
                                ReportSignatureInfo(
                                    kind = ReportSignatureInfo.Kind.forValue(s.kind),
                                    signerName = s.signerName,
                                    signedAt = s.signedAt,
                                    comment = s.comment,
                                    notSignedReason = s.notSignedReason,
                                    contentHash = s.contentHash,
                                    verificationCode = s.verificationCode,
                                    pdfAvailable = s.pdfSha256 != null,
                                    emailed = repo.deliveryCount(report.id) > 0,
                                )
                            },
                        invoice =
                            invoices
                                .invoiceOf(
                                    SOURCE_REPORTS,
                                    listOf(report.id),
                                )[report.id]
                                ?.let { invoices.statuses(listOf(it))[it] }
                                ?.let {
                                    ReportInvoiceInfo(
                                        invoiceId = it.invoiceId,
                                        state = ReportInvoiceInfo.State.forValue(it.state.name),
                                        overdue = it.overdue,
                                        number = it.number,
                                        documentStatus =
                                            it.documentStatus?.let { s ->
                                                de.erika.contract.model.DocumentStatus
                                                    .forValue(s.name)
                                            },
                                        dueDate = it.dueDate,
                                        gross = it.gross?.toPlainString(),
                                    )
                                },
                    ),
                timeEntries =
                    times.map {
                        ReportTimeEntry(
                            id = it.id,
                            technicianId = it.technicianId,
                            technicianName = names[it.technicianId] ?: "?",
                            kind = TimeEntryKind.forValue(it.kind),
                            startedAt = it.startedAt,
                            endedAt = it.endedAt,
                            note = it.note,
                        )
                    },
                materials =
                    materials.map {
                        ReportMaterialLine(
                            id = it.id,
                            articleId = it.articleId,
                            articleNumber = it.articleId?.let { a -> articleRefs[a]?.articleNumber },
                            description = it.description,
                            quantity = it.quantity.toPlainString(),
                            unitCode = it.unitCode,
                        )
                    },
                aufmass =
                    repo.aufmassOf(report.id).map { p ->
                        ReportAufmassPosition(
                            id = p.id,
                            label = p.label,
                            kind = AufmassKind.forValue(p.kind),
                            length = p.length?.toPlainString(),
                            width = p.width?.toPlainString(),
                            height = p.height?.toPlainString(),
                            count = p.count?.toPlainString(),
                            expression = p.expression,
                            deductions =
                                p.deductions.map {
                                    AufmassDeduction(
                                        label = it.label,
                                        length = it.length?.toPlainString(),
                                        width = it.width?.toPlainString(),
                                        height = it.height?.toPlainString(),
                                        count = it.count?.toPlainString(),
                                    )
                                },
                            result = p.result.toPlainString(),
                            unitCode = p.unitCode,
                        )
                    },
                checklist =
                    items.map {
                        ReportChecklistItem(
                            key = it.key,
                            label = it.label,
                            kind = ChecklistItemKind.forValue(it.kind.name),
                            required = it.required,
                            value = answers[it.key]?.value,
                        )
                    },
                photos =
                    photos.map {
                        ReportPhoto(
                            id = it.id,
                            caption = it.caption,
                            category = PhotoCategory.forValue(it.category),
                            checklistItemKey = it.checklistItemKey,
                            state = PhotoState.forValue(it.state),
                            rejectReason = it.rejectReason?.let(ReportPhoto.RejectReason::forValue),
                            takenAt = it.takenAt,
                            authorName = names[it.authorId] ?: "?",
                            width = it.width,
                            height = it.height,
                        )
                    },
                versions =
                    repo.versionsOf(order.id).map { v ->
                        ReportVersion(
                            id = v.id,
                            version = v.version,
                            state = ReportState.forValue(v.state.name),
                            signedAt = repo.signatureOf(v.id)?.signedAt,
                        )
                    },
            ),
        )
    }
}

/** A PDF as a download: attachment, nosniff, sandboxed (the `download` rules, ADR 0025). */
internal fun pdf(
    bytes: ByteArray,
    fileName: String,
): ResponseEntity<Resource> = download(bytes, fileName, MediaType.APPLICATION_PDF)

/** The source module name under which reports are billed (`reportinvoicing`). */
private const val SOURCE_REPORTS = "reports"

/** A CSV cell: quoted when needed; formula-like text gets a leading apostrophe (CSV injection). */
private fun csvCell(value: String): String {
    val v = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
    return if (v.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}

internal fun download(
    bytes: ByteArray,
    fileName: String,
    type: MediaType,
): ResponseEntity<Resource> {
    val headers = HttpHeaders()
    headers.contentType = type
    headers.contentDisposition = ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build()
    headers.set("X-Content-Type-Options", "nosniff")
    headers.set("Content-Security-Policy", "default-src 'none'; sandbox")
    headers.cacheControl = CacheControl.noStore().cachePrivate().headerValue
    return ResponseEntity.ok().headers(headers).body(ByteArrayResource(bytes))
}
