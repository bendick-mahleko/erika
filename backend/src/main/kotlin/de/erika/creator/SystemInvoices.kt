package de.erika.creator

import de.erika.invoicecore.write.OutputFormat
import de.erika.platform.web.Idempotency
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** One line of a system invoice: net price per unit, VAT rate for domestic invoices. */
data class SystemInvoiceLine(
    val name: String,
    val quantity: BigDecimal,
    val netPrice: BigDecimal,
    val unitCode: String = "C62",
    val vatRate: LineVatRate = LineVatRate.STANDARD_19,
    val description: String? = null,
)

/** An invoice the system issues in its own name, e.g. ERIKA's invoice for a paid subscription. */
data class SystemInvoiceRequest(
    /** Unique per invoice (e.g. the Stripe invoice ID): a repeated request returns the first invoice. */
    val reference: String,
    val buyer: BuyerSnapshot,
    val vatTreatment: VatTreatment,
    val lines: List<SystemInvoiceLine>,
    val issueDate: LocalDate,
    val dueDate: LocalDate? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val paymentTerms: String? = null,
    val note: String? = null,
    val format: OutputFormat = OutputFormat.ZUGFERD_EN16931,
    /** Emailed right after issuing, with the file attached and archived (CR-07). */
    val sendTo: List<String> = emptyList(),
)

/**
 * Published API of `creator` for invoices issued by the system rather than a user (ADR 0033;
 * build plan gap 3): `billing` uses it for ERIKA's own invoices. Runs in the caller's
 * transaction, which must be scoped to the issuing workspace. The draft goes through the normal
 * checks, numbering, validation (must be VALID or WARNING) and archiving in the worker.
 */
@Service
class SystemInvoices internal constructor(
    private val drafts: DraftRepository,
    private val creator: Creator,
    private val idempotency: Idempotency,
    private val events: ApplicationEventPublisher,
) {
    /** Creates and queues the invoice; returns its ID (the same one for a repeated [SystemInvoiceRequest.reference]). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun issue(
        issuerWorkspaceId: UUID,
        request: SystemInvoiceRequest,
    ): UUID =
        idempotency
            .once(issuerWorkspaceId, "system:${request.reference}", OPERATION) {
                val id = UUID.randomUUID()
                val content =
                    DraftContent(
                        format = request.format,
                        buyer = request.buyer,
                        vatTreatment = request.vatTreatment,
                        issueDate = request.issueDate,
                        deliveryDate = if (request.periodStart == null) request.issueDate else null,
                        periodStart = request.periodStart,
                        periodEnd = request.periodEnd,
                        dueDate = request.dueDate,
                        paymentTerms = request.paymentTerms,
                        note = request.note,
                        lines =
                            request.lines.map {
                                DraftLine(
                                    name = it.name,
                                    description = it.description,
                                    quantity = it.quantity,
                                    unitCode = it.unitCode,
                                    netPrice = it.netPrice,
                                    vatRate = if (request.vatTreatment == VatTreatment.DOMESTIC) it.vatRate else null,
                                )
                            },
                    )
                drafts.insert(id, issuerWorkspaceId, InvoiceKind.INVOICE, null, content, null, request.sendTo)
                events.publishEvent(InvoiceDraftCreated(issuerWorkspaceId, null, id, InvoiceKind.INVOICE, null))
                creator.requestIssue(issuerWorkspaceId, null, id)
                id
            }.resourceId

    private companion object {
        const val OPERATION = "systemInvoice"
    }
}
