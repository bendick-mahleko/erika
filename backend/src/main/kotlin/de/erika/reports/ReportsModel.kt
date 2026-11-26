package de.erika.reports

import de.erika.platform.audit.AuditableEvent
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** OPEN while the technicians record; COMPLETED once mandatory checklist items are answered and no timer runs. */
enum class ReportState {
    OPEN,
    COMPLETED,

    /** Signed by the customer; never changes again (M-MO-10). */
    SIGNED,

    /** Sent without a signature, with a reason (M-BR-03); never changes again either. */
    NOT_SIGNED,
    ;

    val locked: Boolean get() = this == SIGNED || this == NOT_SIGNED
}

/** A job report (version 1 in Stage 5; signed versions follow in Stage 7). */
data class Report(
    val id: UUID,
    val workOrderId: UUID,
    val version: Int,
    val state: ReportState,
    val workDescription: String?,
    val completedAt: OffsetDateTime?,
    val completedBy: UUID?,
    val createdBy: UUID,
    val createdAt: OffsetDateTime?,
    /** The version this correction is based on (M-BR-01). */
    val basedOn: UUID? = null,
)

/** The customer's signature, or the reason it is missing (M-MO-10, M-BR-03). */
data class ReportSignature(
    val id: UUID,
    val reportId: UUID,
    val kind: String,
    val contentHash: String,
    val signerName: String?,
    val signedAt: OffsetDateTime,
    val comment: String?,
    val notSignedReason: String?,
    val strokes: String?,
    val signaturePng: ByteArray?,
    val verificationCode: String,
    val downloadKeyHash: ByteArray,
    val customerEmail: String?,
    val createdBy: UUID,
    val deviceId: UUID?,
    val pdfSha256: String? = null,
)

/** The archived master PDF of a signed report (audit only; no sync change). */
data class ReportPdfArchived(
    override val workspaceId: UUID,
    val reportId: UUID,
    val sha256: String,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "reports.report.pdf_archived"
    override val details get() = mapOf("id" to reportId.toString(), "sha256" to sha256)
}

/** The report PDF was emailed to the customer (M-MO-11); the message is archived. */
data class ReportSent(
    override val workspaceId: UUID,
    val reportId: UUID,
    val emlSha256: String,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "reports.report.sent"
    override val details get() = mapOf("id" to reportId.toString(), "eml" to emlSha256)
}

/** Raw times as recorded (M-MO-03, M-BR-04); [endedAt] is null while the timer runs. */
data class TimeEntry(
    val id: UUID,
    val reportId: UUID,
    val authorId: UUID,
    val technicianId: UUID,
    val kind: String,
    val startedAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val note: String?,
    val deleted: Boolean = false,
)

/** Material used (M-MO-04): an article or free text, with quantity and unit. */
data class MaterialLine(
    val id: UUID,
    val reportId: UUID,
    val authorId: UUID,
    val articleId: UUID?,
    val description: String,
    val quantity: BigDecimal,
    val unitCode: String,
    val deleted: Boolean = false,
)

data class AufmassDeduction(
    val label: String?,
    val length: BigDecimal?,
    val width: BigDecimal?,
    val height: BigDecimal?,
    val count: BigDecimal?,
)

/** An Aufmaß position (M-MO-06); [result] is calculated by [AufmassRules]. */
data class AufmassPosition(
    val id: UUID,
    val reportId: UUID,
    val authorId: UUID,
    val label: String,
    val kind: String,
    val length: BigDecimal?,
    val width: BigDecimal?,
    val height: BigDecimal?,
    val count: BigDecimal?,
    val expression: String?,
    val deductions: List<AufmassDeduction>,
    val result: BigDecimal,
    val ruleVersion: Int,
    val unitCode: String,
    val deleted: Boolean = false,
)

/** The answer to one checklist item (M-MO-07); [authorId] answered last. */
data class ChecklistAnswer(
    val id: UUID,
    val reportId: UUID,
    val itemKey: String,
    val value: String?,
    val authorId: UUID,
)

/**
 * A report or one of its records was created, changed, deleted, restored, completed or
 * reopened. Only which record is audited, not its content (customer data, NF-10).
 */
data class ReportsChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    /** The sync entity: report, time_entry, material_line, aufmass_position, checklist_answer. */
    val kind: String,
    val id: UUID,
    val change: String,
) : AuditableEvent {
    override val action = "reports.$kind.$change"
    override val details get() = mapOf("id" to id.toString())
}
