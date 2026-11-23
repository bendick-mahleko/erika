package de.erika.creator

import de.erika.documents.DocumentStatus
import de.erika.documents.Documents
import de.erika.platform.tenancy.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Records delivery results reported by the mail provider (CR-07 delivery log). The event
 * arrives without a workspace: the message ID is looked up with a SECURITY DEFINER function
 * that returns only IDs, then the change runs in a transaction scoped to that workspace.
 */
@Service
class MailEvents internal constructor(
    private val jdbc: JdbcClient,
    private val tenant: TenantContext,
    private val deliveries: DeliveryRepository,
    private val issuedRepo: IssuedRepository,
    private val documents: Documents,
    private val events: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    /** [event]: DELIVERED, BOUNCED, FAILED or COMPLAINED. Unknown message IDs are ignored. */
    fun record(
        messageId: String,
        event: String,
        detail: String?,
    ) {
        tx.executeWithoutResult {
            val (workspaceId, deliveryId) =
                jdbc
                    .sql("SELECT workspace_id, delivery_id FROM mail_delivery_for_message(:id)")
                    .param("id", messageId)
                    .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
                    .optional()
                    .orElse(null) ?: return@executeWithoutResult log.info("Mail event for an unknown message ignored")
            tenant.enterWorkspace(workspaceId)
            val status =
                if (event ==
                    "DELIVERED"
                ) {
                    DeliveryStatus.DELIVERED
                } else if (event == "FAILED") {
                    DeliveryStatus.FAILED
                } else {
                    DeliveryStatus.BOUNCED
                }
            val previous = deliveries.updateStatus(deliveryId, status, detail) ?: return@executeWithoutResult
            if (previous == status) return@executeWithoutResult
            val delivery = deliveries.find(deliveryId)!!
            val issued = issuedRepo.find(delivery.invoiceId)!!
            if (status != DeliveryStatus.DELIVERED) {
                val current = documents.statuses(listOf(issued.documentId))[issued.documentId]
                if (current == DocumentStatus.SENT || current == DocumentStatus.ISSUED) {
                    val note = listOfNotNull(delivery.recipient, detail).joinToString(": ")
                    documents.changeStatus(workspaceId, issued.documentId, DocumentStatus.DELIVERY_FAILED, note.take(500), null)
                }
            }
            events.publishEvent(InvoiceDeliveryChanged(workspaceId, issued.id, deliveryId, status))
        }
    }
}
