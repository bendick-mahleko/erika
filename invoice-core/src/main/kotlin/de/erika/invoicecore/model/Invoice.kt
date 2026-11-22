package de.erika.invoicecore.model

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate

/**
 * EN 16931 business term(s) a property carries, e.g. `BT-1` or `BG-4`.
 * Shared types (addresses, contacts, periods, allowances) list the IDs of every
 * context they're used in. Used for labels in the viewer and for the model
 * completeness test.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class Bt(
    vararg val ids: String,
)

/**
 * The neutral invoice model (ADR 0005): one structure for XRechnung UBL, XRechnung
 * CII and ZUGFeRD/Factur-X, following the EN 16931-1 semantic model.
 *
 * Parsing is lenient: almost everything is nullable, so incomplete invoices
 * (e.g. ZUGFeRD MINIMUM) can still be shown. Whether mandatory content is present
 * is decided by validation, not by the parser.
 *
 * Amounts keep the scale written in the file ("100.00" stays 100.00).
 */
public data class Invoice(
    @Bt("BT-1") val number: String?,
    @Bt("BT-2") val issueDate: LocalDate?,
    @Bt("BT-3") val typeCode: String?,
    @Bt("BT-5") val currency: String?,
    @Bt("BT-6") val vatAccountingCurrency: String? = null,
    @Bt("BT-7") val taxPointDate: LocalDate? = null,
    @Bt("BT-8") val vatPointDateCode: String? = null,
    @Bt("BT-9") val dueDate: LocalDate? = null,
    @Bt("BT-10") val buyerReference: String? = null,
    @Bt("BT-11") val projectReference: String? = null,
    @Bt("BT-12") val contractReference: String? = null,
    @Bt("BT-13") val purchaseOrderReference: String? = null,
    @Bt("BT-14") val salesOrderReference: String? = null,
    @Bt("BT-15") val receivingAdviceReference: String? = null,
    @Bt("BT-16") val despatchAdviceReference: String? = null,
    @Bt("BT-17") val tenderOrLotReference: String? = null,
    @Bt("BT-18") val invoicedObjectIdentifier: Identifier? = null,
    @Bt("BT-19") val buyerAccountingReference: String? = null,
    @Bt("BT-20") val paymentTerms: String? = null,
    @Bt("BG-1") val notes: List<Note> = emptyList(),
    @Bt("BG-2", "BT-23") val businessProcessType: String? = null,
    @Bt("BT-24") val specificationIdentifier: String? = null,
    @Bt("BG-3") val precedingInvoices: List<PrecedingInvoiceReference> = emptyList(),
    @Bt("BG-4") val seller: Seller = Seller(),
    @Bt("BG-7") val buyer: Buyer = Buyer(),
    @Bt("BG-10") val payee: Payee? = null,
    @Bt("BG-11") val sellerTaxRepresentative: TaxRepresentative? = null,
    @Bt("BG-13") val delivery: Delivery? = null,
    @Bt("BG-16") val paymentInstructions: PaymentInstructions? = null,
    @Bt("BG-20") val allowances: List<AllowanceCharge> = emptyList(),
    @Bt("BG-21") val charges: List<AllowanceCharge> = emptyList(),
    @Bt("BG-22") val totals: DocumentTotals = DocumentTotals(),
    @Bt("BG-23") val vatBreakdown: List<VatBreakdown> = emptyList(),
    @Bt("BG-24") val supportingDocuments: List<SupportingDocument> = emptyList(),
    @Bt("BG-25") val lines: List<InvoiceLine> = emptyList(),
    /** XRechnung extension BG-DEX-09: amounts collected for third parties; added to the amount due. */
    @Bt("BG-DEX-09") val thirdPartyPayments: List<ThirdPartyPayment> = emptyList(),
) {
    /** Credit note (381) or a corrective type, where amounts reduce what was invoiced. */
    val isCreditNote: Boolean get() = typeCode == TYPE_CREDIT_NOTE

    public companion object {
        public const val TYPE_COMMERCIAL_INVOICE: String = "380"
        public const val TYPE_CREDIT_NOTE: String = "381"
        public const val TYPE_CORRECTED_INVOICE: String = "384"
        public const val TYPE_SELF_BILLED_INVOICE: String = "389"
        public const val TYPE_PARTIAL_CONSTRUCTION_INVOICE: String = "875"
        public const val TYPE_PARTIAL_FINAL_CONSTRUCTION_INVOICE: String = "876"
        public const val TYPE_FINAL_CONSTRUCTION_INVOICE: String = "877"
    }
}

/** An identifier with its optional scheme (e.g. GLN `0088`, Leitweg-ID `0204`). */
public data class Identifier(
    val value: String,
    val scheme: String? = null,
    val schemeVersion: String? = null,
)

public data class Note(
    @Bt("BT-21") val subjectCode: String? = null,
    @Bt("BT-22") val text: String,
)

public data class PrecedingInvoiceReference(
    @Bt("BT-25") val number: String,
    @Bt("BT-26") val issueDate: LocalDate? = null,
)

public data class Address(
    @Bt("BT-35", "BT-50", "BT-64", "BT-75") val line1: String? = null,
    @Bt("BT-36", "BT-51", "BT-65", "BT-76") val line2: String? = null,
    @Bt("BT-162", "BT-163", "BT-164", "BT-165") val line3: String? = null,
    @Bt("BT-37", "BT-52", "BT-66", "BT-77") val city: String? = null,
    @Bt("BT-38", "BT-53", "BT-67", "BT-78") val postCode: String? = null,
    @Bt("BT-39", "BT-54", "BT-68", "BT-79") val subdivision: String? = null,
    @Bt("BT-40", "BT-55", "BT-69", "BT-80") val countryCode: String? = null,
)

public data class Contact(
    @Bt("BT-41", "BT-56") val point: String? = null,
    @Bt("BT-42", "BT-57") val phone: String? = null,
    @Bt("BT-43", "BT-58") val email: String? = null,
)

public data class Seller(
    @Bt("BT-27") val name: String? = null,
    @Bt("BT-28") val tradingName: String? = null,
    @Bt("BT-29") val identifiers: List<Identifier> = emptyList(),
    @Bt("BT-30") val legalRegistrationId: Identifier? = null,
    @Bt("BT-31") val vatId: String? = null,
    @Bt("BT-32") val taxRegistrationId: String? = null,
    @Bt("BT-33") val additionalLegalInformation: String? = null,
    @Bt("BT-34") val electronicAddress: Identifier? = null,
    @Bt("BG-5") val address: Address? = null,
    @Bt("BG-6") val contact: Contact? = null,
)

public data class Buyer(
    @Bt("BT-44") val name: String? = null,
    @Bt("BT-45") val tradingName: String? = null,
    @Bt("BT-46") val identifier: Identifier? = null,
    @Bt("BT-47") val legalRegistrationId: Identifier? = null,
    @Bt("BT-48") val vatId: String? = null,
    @Bt("BT-49") val electronicAddress: Identifier? = null,
    @Bt("BG-8") val address: Address? = null,
    @Bt("BG-9") val contact: Contact? = null,
)

public data class Payee(
    @Bt("BT-59") val name: String? = null,
    @Bt("BT-60") val identifier: Identifier? = null,
    @Bt("BT-61") val legalRegistrationId: Identifier? = null,
)

public data class TaxRepresentative(
    @Bt("BT-62") val name: String? = null,
    @Bt("BT-63") val vatId: String? = null,
    @Bt("BG-12") val address: Address? = null,
)

public data class Period(
    @Bt("BT-73", "BT-134") val start: LocalDate? = null,
    @Bt("BT-74", "BT-135") val end: LocalDate? = null,
)

public data class Delivery(
    @Bt("BT-70") val deliverToName: String? = null,
    @Bt("BT-71") val locationIdentifier: Identifier? = null,
    @Bt("BT-72") val actualDeliveryDate: LocalDate? = null,
    @Bt("BG-14") val invoicingPeriod: Period? = null,
    @Bt("BG-15") val deliverToAddress: Address? = null,
)

public data class PaymentInstructions(
    @Bt("BT-81") val meansTypeCode: String? = null,
    @Bt("BT-82") val meansText: String? = null,
    @Bt("BT-83") val remittanceInformation: String? = null,
    @Bt("BG-17") val creditTransfers: List<CreditTransfer> = emptyList(),
    @Bt("BG-18") val paymentCard: PaymentCard? = null,
    @Bt("BG-19") val directDebit: DirectDebit? = null,
)

public data class CreditTransfer(
    @Bt("BT-84") val accountId: String,
    @Bt("BT-85") val accountName: String? = null,
    @Bt("BT-86") val serviceProviderId: String? = null,
)

public data class PaymentCard(
    @Bt("BT-87") val primaryAccountNumber: String,
    @Bt("BT-88") val holderName: String? = null,
)

public data class DirectDebit(
    @Bt("BT-89") val mandateReference: String? = null,
    @Bt("BT-90") val creditorId: String? = null,
    @Bt("BT-91") val debitedAccountId: String? = null,
)

/** Document-level (BG-20/21) or line-level (BG-27/28) allowance or charge. */
public data class AllowanceCharge(
    @Bt("BT-92", "BT-99", "BT-136", "BT-141") val amount: BigDecimal?,
    @Bt("BT-93", "BT-100", "BT-137", "BT-142") val baseAmount: BigDecimal? = null,
    @Bt("BT-94", "BT-101", "BT-138", "BT-143") val percentage: BigDecimal? = null,
    @Bt("BT-95", "BT-102") val vatCategoryCode: String? = null,
    @Bt("BT-96", "BT-103") val vatRate: BigDecimal? = null,
    @Bt("BT-97", "BT-104", "BT-139", "BT-144") val reason: String? = null,
    @Bt("BT-98", "BT-105", "BT-140", "BT-145") val reasonCode: String? = null,
)

public data class DocumentTotals(
    @Bt("BT-106") val lineNetTotal: BigDecimal? = null,
    @Bt("BT-107") val allowanceTotal: BigDecimal? = null,
    @Bt("BT-108") val chargeTotal: BigDecimal? = null,
    @Bt("BT-109") val totalWithoutVat: BigDecimal? = null,
    @Bt("BT-110") val totalVat: BigDecimal? = null,
    @Bt("BT-111") val totalVatInAccountingCurrency: BigDecimal? = null,
    @Bt("BT-112") val totalWithVat: BigDecimal? = null,
    @Bt("BT-113") val paidAmount: BigDecimal? = null,
    @Bt("BT-114") val roundingAmount: BigDecimal? = null,
    @Bt("BT-115") val amountDue: BigDecimal? = null,
)

public data class VatBreakdown(
    @Bt("BT-116") val taxableAmount: BigDecimal?,
    @Bt("BT-117") val taxAmount: BigDecimal?,
    @Bt("BT-118") val categoryCode: String?,
    @Bt("BT-119") val rate: BigDecimal? = null,
    @Bt("BT-120") val exemptionReason: String? = null,
    @Bt("BT-121") val exemptionReasonCode: String? = null,
)

public data class SupportingDocument(
    @Bt("BT-122") val reference: String,
    @Bt("BT-123") val description: String? = null,
    @Bt("BT-124") val externalLocation: String? = null,
    @Bt("BT-125") val attachment: Attachment? = null,
)

/**
 * An embedded attachment (BT-125), already Base64-decoded. Equality is based on
 * content hash, MIME code and file name.
 */
public class Attachment(
    public val mimeCode: String?,
    public val filename: String?,
    content: ByteArray,
) {
    private val bytes: ByteArray = content.copyOf()

    public val content: ByteArray get() = bytes.copyOf()
    public val size: Int get() = bytes.size
    public val sha256: String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean =
        other is Attachment && other.sha256 == sha256 && other.mimeCode == mimeCode && other.filename == filename

    override fun hashCode(): Int = sha256.hashCode()

    override fun toString(): String = "Attachment(mimeCode=$mimeCode, filename=$filename, size=$size, sha256=$sha256)"
}

public data class InvoiceLine(
    @Bt("BT-126") val id: String?,
    @Bt("BT-127") val note: String? = null,
    @Bt("BT-128") val objectIdentifier: Identifier? = null,
    @Bt("BT-129") val quantity: BigDecimal? = null,
    @Bt("BT-130") val unitCode: String? = null,
    @Bt("BT-131") val netAmount: BigDecimal? = null,
    @Bt("BT-132") val orderLineReference: String? = null,
    @Bt("BT-133") val buyerAccountingReference: String? = null,
    @Bt("BG-26") val period: Period? = null,
    @Bt("BG-27") val allowances: List<AllowanceCharge> = emptyList(),
    @Bt("BG-28") val charges: List<AllowanceCharge> = emptyList(),
    @Bt("BG-29") val price: PriceDetails = PriceDetails(),
    @Bt("BG-30", "BT-151") val vatCategoryCode: String? = null,
    @Bt("BT-152") val vatRate: BigDecimal? = null,
    @Bt("BG-31") val item: Item = Item(),
    /** XRechnung extension: sub invoice lines (UBL `cac:SubInvoiceLine`). Not part of the totals. */
    val subLines: List<InvoiceLine> = emptyList(),
)

public data class PriceDetails(
    @Bt("BT-146") val netPrice: BigDecimal? = null,
    @Bt("BT-147") val discount: BigDecimal? = null,
    @Bt("BT-148") val grossPrice: BigDecimal? = null,
    @Bt("BT-149") val baseQuantity: BigDecimal? = null,
    @Bt("BT-150") val baseQuantityUnitCode: String? = null,
)

public data class Item(
    @Bt("BT-153") val name: String? = null,
    @Bt("BT-154") val description: String? = null,
    @Bt("BT-155") val sellerId: String? = null,
    @Bt("BT-156") val buyerId: String? = null,
    @Bt("BT-157") val standardId: Identifier? = null,
    @Bt("BT-158") val classifications: List<Identifier> = emptyList(),
    @Bt("BT-159") val originCountry: String? = null,
    @Bt("BG-32") val attributes: List<ItemAttribute> = emptyList(),
)

/** XRechnung extension: third-party payment (BG-DEX-09). */
public data class ThirdPartyPayment(
    @Bt("BT-DEX-001") val type: String?,
    @Bt("BT-DEX-002") val amount: java.math.BigDecimal?,
    @Bt("BT-DEX-003") val description: String? = null,
)

public data class ItemAttribute(
    @Bt("BT-160") val name: String,
    @Bt("BT-161") val value: String,
)
