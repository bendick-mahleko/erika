package de.erika.creator

import de.erika.documents.DocumentStatus
import de.erika.documents.Documents
import de.erika.invoicecore.write.OutputFormat
import de.erika.platform.web.ErikaException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** A line of an invoice made from another module's records (e.g. MARA reports). */
data class SourceInvoiceLine(
    val name: String,
    val quantity: BigDecimal,
    val unitCode: String,
    val netPrice: BigDecimal,
    val vatRate: LineVatRate = LineVatRate.STANDARD_19,
    val description: String? = null,
    val articleId: UUID? = null,
    val articleNumber: String? = null,
    /** Labour or travel: part of the § 35a EStG share for private customers. */
    val laborCost: Boolean = false,
)

/** An embedded file (BG-24): an archived original of the workspace. */
data class SourceInvoiceAttachment(
    val sha256: String,
    val fileName: String,
    val mimeCode: String,
    val reference: String,
    val description: String?,
)

data class SourceInvoiceRequest(
    val customerId: UUID,
    /** The source's module and the records billed (each can be billed once). */
    val sourceModule: String,
    val sourceIds: List<UUID>,
    val lines: List<SourceInvoiceLine>,
    val attachments: List<SourceInvoiceAttachment> = emptyList(),
    /** Null: XRechnung CII for business customers with a Leitweg-ID, else the firm's default; ZUGFeRD for private customers. */
    val format: OutputFormat? = null,
    val deliveryDate: LocalDate? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val propertyRelated: Boolean = false,
    val note: String? = null,
)

/** Where an invoice made from a source stands, for the source's module. */
data class SourceInvoiceStatus(
    val invoiceId: UUID,
    val state: DraftState,
    val number: String?,
    val documentStatus: DocumentStatus?,
    val dueDate: LocalDate?,
    val gross: BigDecimal?,
    val overdue: Boolean,
)

/**
 * Published API of `creator` for invoices made from other modules' records (MARA Stage 8): the
 * draft gets the customer's data, the lines and the embedded files, and is issued right away by
 * `InvoiceIssuer` in the worker (number, generation, validation, archive). A source record can be
 * on one invoice only. Runs in the caller's tenant transaction.
 */
@Service
class SourceInvoices internal constructor(
    private val drafts: DraftRepository,
    private val sources: DraftSourceRepository,
    private val customers: CustomerRepository,
    private val settingsRepo: InvoiceSettingsRepository,
    private val issuedRepo: IssuedRepository,
    private val documents: Documents,
    private val creator: Creator,
    private val events: ApplicationEventPublisher,
) {
    /** Creates the draft and requests issuing; 409 `ALREADY_INVOICED` if a source is on another invoice. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun create(
        workspaceId: UUID,
        actorId: UUID,
        request: SourceInvoiceRequest,
    ): UUID {
        val customer =
            customers.find(request.customerId)
                ?: throw ErikaException("CUSTOMER_NOT_FOUND", HttpStatus.BAD_REQUEST, "The customer doesn't exist.")
        val private = customer.customerType == CustomerType.PRIVATE
        val settings = settingsRepo.find(workspaceId)
        val format =
            request.format ?: when {
                // Private customers get the ZUGFeRD PDF, which reads like a normal PDF (build plan Stage 8 proposal).
                private -> OutputFormat.ZUGFERD_EN16931

                customer.preferredFormat != null -> customer.preferredFormat

                !customer.leitwegId.isNullOrBlank() -> OutputFormat.XRECHNUNG_CII

                else -> settings.defaultFormat
            }
        val treatment = customer.vatTreatment
        val content =
            DraftContent(
                format = format,
                customerId = customer.id,
                buyer =
                    BuyerSnapshot(
                        customerNumber = customer.customerNumber,
                        name = customer.name,
                        street = customer.street,
                        additionalLine = customer.additionalLine,
                        postalCode = customer.postalCode,
                        city = customer.city,
                        countryCode = customer.countryCode,
                        vatId = customer.vatId,
                        email = customer.email,
                        contactName = customer.contactName,
                    ),
                vatTreatment = treatment,
                buyerReference = customer.leitwegId ?: customer.buyerReference,
                deliveryDate = request.deliveryDate.takeIf { request.periodStart == null },
                periodStart = request.periodStart,
                periodEnd = request.periodEnd,
                note = request.note,
                lines =
                    request.lines.map {
                        DraftLine(
                            articleId = it.articleId,
                            articleNumber = it.articleNumber,
                            name = it.name,
                            description = it.description,
                            quantity = it.quantity,
                            unitCode = it.unitCode,
                            netPrice = it.netPrice,
                            vatRate = if (treatment == VatTreatment.DOMESTIC) it.vatRate else null,
                            laborCost = it.laborCost,
                        )
                    },
                attachments =
                    request.attachments.map {
                        DraftAttachment(
                            it.sha256,
                            it.fileName,
                            it.mimeCode,
                            it.reference,
                            it.description,
                        )
                    },
                privateCustomer = private,
                propertyRelated = private && request.propertyRelated,
                source = DraftSource(request.sourceModule, request.sourceIds),
            )
        val id = UUID.randomUUID()
        drafts.insert(id, workspaceId, InvoiceKind.INVOICE, null, content, actorId)
        try {
            request.sourceIds.forEach { sources.insert(workspaceId, id, request.sourceModule, it) }
        } catch (e: DuplicateKeyException) {
            throw ErikaException("ALREADY_INVOICED", HttpStatus.CONFLICT, "A report is already on another invoice.")
        }
        events.publishEvent(InvoiceDraftCreated(workspaceId, actorId, id, InvoiceKind.INVOICE, null))
        creator.requestIssue(workspaceId, actorId, id)
        return id
    }

    /** The invoice a source record is on, if any (a draft or an issued invoice). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun invoiceOf(
        module: String,
        sourceIds: Collection<UUID>,
    ): Map<UUID, UUID> = sources.invoicesOf(module, sourceIds)

    @Transactional(propagation = Propagation.MANDATORY)
    fun statuses(invoiceIds: Collection<UUID>): Map<UUID, SourceInvoiceStatus> {
        val today = LocalDate.now(Creator.BERLIN)
        return invoiceIds
            .distinct()
            .mapNotNull { id ->
                val draft = drafts.find(id) ?: return@mapNotNull null
                val issued = issuedRepo.find(id)
                val status = issued?.let { documents.statuses(listOf(it.documentId))[it.documentId] }
                id to
                    SourceInvoiceStatus(
                        invoiceId = id,
                        state = draft.state,
                        number = issued?.number,
                        documentStatus = status,
                        dueDate = issued?.dueDate,
                        gross = issued?.gross,
                        overdue = issued != null && Creator.isOverdue(issued, status, today),
                    )
            }.toMap()
    }
}

@Repository
internal class DraftSourceRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        workspaceId: UUID,
        draftId: UUID,
        module: String,
        sourceId: UUID,
    ) {
        jdbc
            .sql(
                "INSERT INTO invoice_draft_source (workspace_id, draft_id, source_module, source_id) VALUES (:ws, :draft, :module, :source)",
            ).param("ws", workspaceId)
            .param("draft", draftId)
            .param("module", module)
            .param("source", sourceId)
            .update()
    }

    fun invoicesOf(
        module: String,
        sourceIds: Collection<UUID>,
    ): Map<UUID, UUID> =
        if (sourceIds.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql(
                    "SELECT source_id, draft_id FROM invoice_draft_source " +
                        "WHERE source_module = :module AND source_id IN (:ids) AND workspace_id = current_workspace_id()",
                ).param("module", module)
                .param("ids", sourceIds)
                .query { rs, _ -> rs.getObject("source_id", UUID::class.java) to rs.getObject("draft_id", UUID::class.java) }
                .list()
                .toMap()
        }
}

/** A payment reminder (M-IV-06). */
internal data class ReminderRow(
    val invoiceId: UUID,
    val level: Int,
    val recipient: String,
    val createdAt: java.time.OffsetDateTime,
)

@Repository
internal class ReminderRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        workspaceId: UUID,
        invoiceId: UUID,
        level: Int,
        recipient: String,
        deliveryId: UUID,
        actorId: UUID?,
    ) {
        jdbc
            .sql(
                "INSERT INTO invoice_reminder (id, workspace_id, invoice_id, level, recipient, delivery_id, created_by) " +
                    "VALUES (gen_random_uuid(), :ws, :invoice, :level, :recipient, :delivery, :actor)",
            ).param("ws", workspaceId)
            .param("invoice", invoiceId)
            .param("level", level)
            .param("recipient", recipient)
            .param("delivery", deliveryId)
            .param("actor", actorId)
            .update()
    }

    fun ofInvoices(invoiceIds: Collection<UUID>): Map<UUID, List<ReminderRow>> =
        if (invoiceIds.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql(
                    "SELECT invoice_id, level, recipient, created_at FROM invoice_reminder " +
                        "WHERE invoice_id IN (:ids) AND workspace_id = current_workspace_id() ORDER BY created_at",
                ).param("ids", invoiceIds)
                .query { rs, _ ->
                    ReminderRow(
                        rs.getObject("invoice_id", UUID::class.java),
                        rs.getInt("level"),
                        rs.getString("recipient"),
                        rs.getObject("created_at", java.time.OffsetDateTime::class.java),
                    )
                }.list()
                .groupBy { it.invoiceId }
        }
}
