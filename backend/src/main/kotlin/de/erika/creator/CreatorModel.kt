package de.erika.creator

import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.write.OutputFormat
import de.erika.invoicecore.write.VatCase
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** What a draft becomes (CR-06): its type code (BT-3) and which number pattern it uses. */
enum class InvoiceKind(
    val typeCode: String,
) {
    INVOICE(Invoice.TYPE_COMMERCIAL_INVOICE),
    CREDIT_NOTE(Invoice.TYPE_CREDIT_NOTE),
    CANCELLATION(Invoice.TYPE_CREDIT_NOTE),
    CORRECTION(Invoice.TYPE_CORRECTED_INVOICE),
    ;

    /** Credit notes and cancellations are numbered with the credit-note pattern. */
    val usesCreditNotePattern: Boolean get() = typeCode == Invoice.TYPE_CREDIT_NOTE

    /** Issuing it replaces the original, which becomes CANCELLED. */
    val cancelsOriginal: Boolean get() = this == CANCELLATION || this == CORRECTION
}

enum class DraftState { DRAFT, ISSUING, ISSUED }

/** How VAT applies to a customer's invoices (CR-04); Kleinunternehmer is a seller setting. */
enum class VatTreatment { DOMESTIC, REVERSE_CHARGE, INTRA_EU }

/** Line rate for DOMESTIC invoices. */
enum class LineVatRate(
    val case: VatCase,
) {
    STANDARD_19(VatCase.STANDARD_19),
    REDUCED_7(VatCase.REDUCED_7),
    ZERO(VatCase.ZERO),
}

/** The customer as printed on the invoice: a copy taken when the customer is chosen. */
data class BuyerSnapshot(
    val customerNumber: String? = null,
    val name: String? = null,
    val street: String? = null,
    val additionalLine: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val countryCode: String? = null,
    val vatId: String? = null,
    val email: String? = null,
    val contactName: String? = null,
)

data class DraftLine(
    val articleId: UUID? = null,
    val articleNumber: String? = null,
    val name: String? = null,
    val description: String? = null,
    val quantity: BigDecimal? = null,
    val unitCode: String? = null,
    val netPrice: BigDecimal? = null,
    val vatRate: LineVatRate? = null,
)

/** Content of `invoice_draft.data` (stored as JSON). Everything may be missing while editing. */
data class DraftContent(
    val format: OutputFormat? = null,
    val customerId: UUID? = null,
    val buyer: BuyerSnapshot = BuyerSnapshot(),
    val vatTreatment: VatTreatment? = null,
    val issueDate: LocalDate? = null,
    val deliveryDate: LocalDate? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val buyerReference: String? = null,
    val orderReference: String? = null,
    val paymentTerms: String? = null,
    val note: String? = null,
    val lines: List<DraftLine> = emptyList(),
) {
    val isXRechnung: Boolean get() = format == OutputFormat.XRECHNUNG_CII || format == OutputFormat.XRECHNUNG_UBL
}

/** A missing or wrong field (CR-01); `field` is a path like `buyer.name` or `lines[0].quantity`. */
data class Problem(
    val field: String,
    val code: String,
)
