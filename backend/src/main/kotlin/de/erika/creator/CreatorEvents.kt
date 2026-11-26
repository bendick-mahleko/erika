package de.erika.creator

import de.erika.platform.audit.AuditableEvent
import java.util.UUID

/** Audited creator events (NF-10). Details carry IDs, numbers and codes only, never invoice content. */
data class InvoiceDraftCreated(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val invoiceId: UUID,
    val kind: InvoiceKind,
    val originalInvoiceId: UUID?,
) : AuditableEvent {
    override val action = "creator.draft.created"
    override val details
        get() =
            buildMap {
                put("invoiceId", invoiceId.toString())
                put("kind", kind.name)
                originalInvoiceId?.let { put("originalInvoiceId", it.toString()) }
            }
}

data class InvoiceDraftDiscarded(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val invoiceId: UUID,
) : AuditableEvent {
    override val action = "creator.draft.discarded"
    override val details get() = mapOf("invoiceId" to invoiceId.toString())
}

data class InvoiceIssueRequested(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val invoiceId: UUID,
) : AuditableEvent {
    override val action = "creator.issue.requested"
    override val details get() = mapOf("invoiceId" to invoiceId.toString())
}

/** A payment reminder for an overdue invoice was queued (MARA M-IV-06). */
data class InvoiceReminderSent(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val invoiceId: UUID,
    val level: Int,
) : AuditableEvent {
    override val action = "creator.invoice.reminder_sent"
    override val details get() = mapOf("invoiceId" to invoiceId.toString(), "level" to level.toString())
}

/** CR-03 / BR-04: a number was used. */
data class InvoiceIssued(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val invoiceId: UUID,
    val number: String,
    val kind: InvoiceKind,
    val format: String,
    val documentId: UUID,
    val sha256: String,
    val validationLevel: String,
    val packId: String,
) : AuditableEvent {
    override val action = "creator.invoice.issued"
    override val details
        get() =
            mapOf(
                "invoiceId" to invoiceId.toString(),
                "number" to number,
                "kind" to kind.name,
                "format" to format,
                "documentId" to documentId.toString(),
                "sha256" to sha256,
                "validationLevel" to validationLevel,
                "packId" to packId,
            )
}

/** Issuing failed (incomplete or invalid); no number was used. */
data class InvoiceIssueRejected(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val invoiceId: UUID,
    val ruleIds: List<String>,
) : AuditableEvent {
    override val action = "creator.issue.rejected"
    override val details get() = mapOf("invoiceId" to invoiceId.toString(), "rules" to ruleIds.distinct().take(20).joinToString(","))
}

data class InvoiceSendRequested(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val invoiceId: UUID,
    val deliveryId: UUID,
) : AuditableEvent {
    override val action = "creator.send.requested"
    override val details get() = mapOf("invoiceId" to invoiceId.toString(), "deliveryId" to deliveryId.toString())
}

/** CR-07: the email went out; the sent message is archived under [emlSha256]. */
data class InvoiceSent(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val invoiceId: UUID,
    val deliveryId: UUID,
    val emlSha256: String,
) : AuditableEvent {
    override val action = "creator.invoice.sent"
    override val details get() = mapOf("invoiceId" to invoiceId.toString(), "deliveryId" to deliveryId.toString(), "emlSha256" to emlSha256)
}

data class InvoiceDeliveryChanged(
    override val workspaceId: UUID,
    val invoiceId: UUID,
    val deliveryId: UUID,
    val status: DeliveryStatus,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "creator.delivery.${status.name.lowercase()}"
    override val details get() = mapOf("invoiceId" to invoiceId.toString(), "deliveryId" to deliveryId.toString())
}
