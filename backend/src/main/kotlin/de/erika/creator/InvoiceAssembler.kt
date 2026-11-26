package de.erika.creator

import de.erika.identity.WorkspaceProfile
import de.erika.invoicecore.model.Address
import de.erika.invoicecore.model.Attachment
import de.erika.invoicecore.model.Buyer
import de.erika.invoicecore.model.Contact
import de.erika.invoicecore.model.CreditTransfer
import de.erika.invoicecore.model.Delivery
import de.erika.invoicecore.model.Identifier
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.InvoiceLine
import de.erika.invoicecore.model.Item
import de.erika.invoicecore.model.Note
import de.erika.invoicecore.model.PaymentInstructions
import de.erika.invoicecore.model.Period
import de.erika.invoicecore.model.PrecedingInvoiceReference
import de.erika.invoicecore.model.PriceDetails
import de.erika.invoicecore.model.Seller
import de.erika.invoicecore.model.SupportingDocument
import de.erika.invoicecore.write.VatCase
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** What an issued original is referenced by (BT-25, BT-26). */
data class OriginalReference(
    val number: String,
    val issueDate: LocalDate,
)

/**
 * Builds the neutral invoice model (invoice-core) from a draft, the seller's settings and
 * profile, the assigned number and the issue date. Amounts are computed by invoice-core's
 * writer (EN 16931 rounding). Also used for the totals preview of a draft.
 */
object InvoiceAssembler {
    private val GERMAN_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** The VAT case of a line under the draft's treatment and the seller's Kleinunternehmer flag (CR-04, BR-06). */
    fun vatCase(
        line: DraftLine,
        treatment: VatTreatment?,
        settings: InvoiceSettings,
    ): VatCase =
        when {
            settings.kleinunternehmer -> VatCase.KLEINUNTERNEHMER
            treatment == VatTreatment.REVERSE_CHARGE -> VatCase.REVERSE_CHARGE
            treatment == VatTreatment.INTRA_EU -> VatCase.INTRA_EU_SUPPLY
            else -> (line.vatRate ?: LineVatRate.STANDARD_19).case
        }

    fun dueDate(
        kind: InvoiceKind,
        content: DraftContent,
        settings: InvoiceSettings,
        issueDate: LocalDate,
    ): LocalDate? = content.dueDate ?: if (kind.usesCreditNotePattern) null else issueDate.plusDays(settings.paymentTermsDays.toLong())

    fun build(
        kind: InvoiceKind,
        content: DraftContent,
        settings: InvoiceSettings,
        seller: WorkspaceProfile,
        number: String?,
        issueDate: LocalDate,
        original: OriginalReference?,
        /** The archived bytes of an attachment (by SHA-256); null leaves the file out (draft preview). */
        attachmentBytes: (String) -> ByteArray? = { null },
    ): Invoice {
        val c = content
        val b = c.buyer
        val due = dueDate(kind, c, settings, issueDate)
        val cases = c.lines.map { vatCase(it, c.vatTreatment, settings) }
        val buyerAddress =
            Address(
                line1 = b.street.clean(),
                line2 = b.additionalLine.clean(),
                postCode = b.postalCode.clean(),
                city = b.city.clean(),
                countryCode = b.countryCode.clean(),
            )
        val noVatId = seller.vatId.isNullOrBlank()
        val terms =
            c.paymentTerms.clean()
                ?: when {
                    kind.usesCreditNotePattern -> "Der Betrag wird Ihnen erstattet oder mit offenen Rechnungen verrechnet."
                    due != null && !due.isAfter(issueDate) -> "Zahlbar sofort ohne Abzug."
                    due != null -> "Zahlbar ohne Abzug bis zum ${due.format(GERMAN_DATE)}."
                    else -> null
                }
        val notes =
            buildList {
                c.note.clean()?.let { add(Note(text = it)) }
                cases.distinct().mapNotNull { it.note }.forEach { add(Note(text = it)) }
                if (c.privateCustomer) {
                    laborShareNote(c, cases)?.let { add(Note(text = it)) }
                    if (c.propertyRelated) add(Note(text = RETENTION_NOTE))
                }
            }
        return Invoice(
            number = number,
            issueDate = issueDate,
            typeCode = kind.typeCode,
            currency = "EUR",
            dueDate = due,
            buyerReference = c.buyerReference.clean(),
            purchaseOrderReference = c.orderReference.clean(),
            paymentTerms = terms,
            notes = notes,
            precedingInvoices = listOfNotNull(original?.let { PrecedingInvoiceReference(it.number, it.issueDate) }),
            seller =
                Seller(
                    name = seller.name,
                    // BR-CO-26: without VAT ID or register entry the tax number identifies the seller (ADR 0031).
                    identifiers =
                        if (noVatId && settings.legalRegistration.isNullOrBlank()) {
                            listOfNotNull(seller.taxNumber.clean()?.let { Identifier(it) })
                        } else {
                            emptyList()
                        },
                    legalRegistrationId = settings.legalRegistration.clean()?.let { Identifier(it) },
                    vatId = seller.vatId.clean(),
                    taxRegistrationId = seller.taxNumber.clean(),
                    electronicAddress = settings.contactEmail.clean()?.let { Identifier(it, "EM") },
                    address =
                        Address(
                            line1 = seller.street.clean(),
                            postCode = seller.postalCode.clean(),
                            city = seller.city.clean(),
                            countryCode = seller.countryCode,
                        ),
                    contact =
                        if (settings.contactName.isNullOrBlank() && settings.contactPhone.isNullOrBlank() &&
                            settings.contactEmail.isNullOrBlank()
                        ) {
                            null
                        } else {
                            Contact(settings.contactName.clean(), settings.contactPhone.clean(), settings.contactEmail.clean())
                        },
                ),
            buyer =
                Buyer(
                    name = b.name.clean(),
                    identifier = b.customerNumber.clean()?.let { Identifier(it) },
                    vatId =
                        b.vatId
                            .clean()
                            ?.uppercase()
                            ?.replace(" ", ""),
                    electronicAddress = b.email.clean()?.let { Identifier(it, "EM") },
                    address = buyerAddress,
                    contact = b.contactName.clean()?.let { Contact(point = it) },
                ),
            delivery =
                Delivery(
                    actualDeliveryDate = c.deliveryDate,
                    invoicingPeriod = if (c.periodStart != null || c.periodEnd != null) Period(c.periodStart, c.periodEnd) else null,
                    // BR-IC-12: intra-EU supplies name the country of delivery.
                    deliverToAddress = if (cases.any { it == VatCase.INTRA_EU_SUPPLY }) buyerAddress else null,
                ),
            paymentInstructions =
                settings.iban.clean()?.let { iban ->
                    PaymentInstructions(
                        meansTypeCode = "58",
                        meansText = "SEPA-Überweisung",
                        remittanceInformation = number,
                        creditTransfers =
                            listOf(
                                CreditTransfer(
                                    iban.replace(" ", "").uppercase(),
                                    settings.accountHolder.clean() ?: seller.name,
                                    settings.bic
                                        .clean()
                                        ?.replace(" ", "")
                                        ?.uppercase(),
                                ),
                            ),
                    )
                },
            supportingDocuments =
                c.attachments.map { a ->
                    SupportingDocument(
                        reference = a.reference,
                        description = a.description,
                        attachment = attachmentBytes(a.sha256)?.let { Attachment(a.mimeCode, a.fileName, it) },
                    )
                },
            lines =
                c.lines.mapIndexed { i, line ->
                    val case = cases[i]
                    InvoiceLine(
                        id = (i + 1).toString(),
                        quantity = line.quantity,
                        unitCode = line.unitCode,
                        price = PriceDetails(netPrice = line.netPrice),
                        vatCategoryCode = case.categoryCode,
                        vatRate = case.rate,
                        item =
                            Item(
                                name = line.name.clean(),
                                description = line.description.clean(),
                                sellerId = line.articleNumber.clean(),
                            ),
                    )
                },
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** § 14 Abs. 4 Nr. 9 UStG: private customers must keep invoices for work on a property for 2 years. */
    const val RETENTION_NOTE: String =
        "Sie sind gesetzlich verpflichtet, diese Rechnung zwei Jahre lang aufzubewahren (§ 14b Abs. 1 Satz 5 UStG)."

    /**
     * The labour share for § 35a EStG (M-BR-07): the labour, machine and travel lines with their
     * VAT, as private customers claim it in their tax return. Null without such lines.
     */
    fun laborShare(
        content: DraftContent,
        cases: List<VatCase>,
    ): Pair<BigDecimal, BigDecimal>? {
        val labour = content.lines.indices.filter { content.lines[it].laborCost }
        if (labour.isEmpty()) return null
        var net = BigDecimal.ZERO
        var vat = BigDecimal.ZERO
        for (i in labour) {
            val line = content.lines[i]
            val lineNet = (line.quantity ?: BigDecimal.ZERO).multiply(line.netPrice ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
            net += lineNet
            vat += lineNet.multiply(cases[i].rate).divide(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        }
        return net to vat
    }

    private fun laborShareNote(
        content: DraftContent,
        cases: List<VatCase>,
    ): String? {
        val (net, vat) = laborShare(content, cases) ?: return null
        val euro = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.GERMANY)
        return "Im Rechnungsbetrag sind Arbeits- und Fahrtkosten nach § 35a EStG von ${euro.format(net + vat)} enthalten " +
            "(netto ${euro.format(net)}, Umsatzsteuer ${euro.format(vat)})."
    }
}
