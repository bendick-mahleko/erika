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
    /** Labour, machine or travel costs: counted in the § 35a EStG share for private customers (MARA, M-BR-07). */
    val laborCost: Boolean = false,
)

/** A file embedded in the e-invoice (BG-24), e.g. a signed job report; the bytes are in the archive. */
data class DraftAttachment(
    /** SHA-256 of the archived original (`archive.Archive`). */
    val sha256: String,
    val fileName: String,
    val mimeCode: String,
    /** BT-122: the document's identifier, e.g. the report's verification code. */
    val reference: String,
    /** BT-123. */
    val description: String? = null,
)

/** Where a draft came from (e.g. MARA reports), so a source can't be invoiced twice. */
data class DraftSource(
    val module: String,
    val ids: List<UUID>,
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
    /** BG-24: files embedded in the e-invoice (MARA: signed reports, M-IV-04). */
    val attachments: List<DraftAttachment> = emptyList(),
    /** A private person: the § 35a EStG labour share is printed (M-BR-07). */
    val privateCustomer: Boolean = false,
    /** Work on a property: the 2-year retention note (§ 14 Abs. 4 Nr. 9 UStG) is printed for private customers. */
    val propertyRelated: Boolean = false,
    val source: DraftSource? = null,
) {
    val isXRechnung: Boolean get() = format == OutputFormat.XRECHNUNG_CII || format == OutputFormat.XRECHNUNG_UBL
}

/** A missing or wrong field (CR-01); `field` is a path like `buyer.name` or `lines[0].quantity`. */
data class Problem(
    val field: String,
    val code: String,
)

/** An overdue invoice with its reminders (MARA M-IV-06). */
internal data class OverdueInvoice(
    val issued: IssuedRow,
    val status: de.erika.documents.DocumentStatus?,
    val reminders: List<ReminderRow>,
    val daysOverdue: Long,
)
