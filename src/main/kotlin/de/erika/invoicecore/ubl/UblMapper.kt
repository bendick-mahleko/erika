package de.erika.invoicecore.ubl

import de.erika.invoicecore.model.Address
import de.erika.invoicecore.model.AllowanceCharge
import de.erika.invoicecore.model.Attachment
import de.erika.invoicecore.model.Buyer
import de.erika.invoicecore.model.Contact
import de.erika.invoicecore.model.CreditTransfer
import de.erika.invoicecore.model.Delivery
import de.erika.invoicecore.model.DirectDebit
import de.erika.invoicecore.model.DocumentTotals
import de.erika.invoicecore.model.Identifier
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.InvoiceLine
import de.erika.invoicecore.model.Item
import de.erika.invoicecore.model.ItemAttribute
import de.erika.invoicecore.model.Note
import de.erika.invoicecore.model.Payee
import de.erika.invoicecore.model.PaymentCard
import de.erika.invoicecore.model.PaymentInstructions
import de.erika.invoicecore.model.Period
import de.erika.invoicecore.model.PrecedingInvoiceReference
import de.erika.invoicecore.model.PriceDetails
import de.erika.invoicecore.model.Seller
import de.erika.invoicecore.model.SupportingDocument
import de.erika.invoicecore.model.TaxRepresentative
import de.erika.invoicecore.model.ThirdPartyPayment
import de.erika.invoicecore.model.VatBreakdown
import de.erika.invoicecore.xml.El
import de.erika.invoicecore.xml.Ns
import de.erika.invoicecore.xml.Warnings
import java.util.Base64

/**
 * Maps a UBL 2.1 Invoice or CreditNote to the neutral model, following the
 * EN 16931-3-2 syntax binding (plus the XRechnung sub-line extension).
 */
internal class UblMapper(
    private val root: El,
    private val warnings: Warnings,
) {
    private val isCreditNote = root.element.namespaceURI == Ns.UBL_CREDIT_NOTE
    private val lineTag = if (isCreditNote) "cac:CreditNoteLine" else "cac:InvoiceLine"
    private val quantityTag = if (isCreditNote) "cbc:CreditedQuantity" else "cbc:InvoicedQuantity"

    fun lineCount(): Int = countLines(root.children(lineTag))

    private fun countLines(lines: List<El>): Int = lines.size + lines.sumOf { countLines(it.children("cac:SubInvoiceLine")) }

    fun map(): Invoice {
        val currency = root.text("cbc:DocumentCurrencyCode")
        val taxCurrency = root.text("cbc:TaxCurrencyCode")
        val additionalDocs = root.children("cac:AdditionalDocumentReference")
        return Invoice(
            number = root.text("cbc:ID"),
            issueDate = root.isoDate("cbc:IssueDate", "BT-2", warnings),
            typeCode = root.text(if (isCreditNote) "cbc:CreditNoteTypeCode" else "cbc:InvoiceTypeCode"),
            currency = currency,
            vatAccountingCurrency = taxCurrency,
            taxPointDate = root.isoDate("cbc:TaxPointDate", "BT-7", warnings),
            vatPointDateCode = root.text("cac:InvoicePeriod/cbc:DescriptionCode"),
            dueDate =
                if (isCreditNote) {
                    root.isoDate("cac:PaymentMeans/cbc:PaymentDueDate", "BT-9", warnings)
                } else {
                    root.isoDate("cbc:DueDate", "BT-9", warnings)
                },
            buyerReference = root.text("cbc:BuyerReference"),
            projectReference =
                if (isCreditNote) {
                    additionalDocs.firstOrNull { it.text("cbc:DocumentTypeCode") == "50" }?.text("cbc:ID")
                } else {
                    root.text("cac:ProjectReference/cbc:ID")
                },
            contractReference = root.text("cac:ContractDocumentReference/cbc:ID"),
            // UBL requires OrderReference/ID even when only BT-14 exists; the binding fills it with "NA".
            purchaseOrderReference = root.text("cac:OrderReference/cbc:ID")?.takeUnless { it == "NA" },
            salesOrderReference = root.text("cac:OrderReference/cbc:SalesOrderID"),
            receivingAdviceReference = root.text("cac:ReceiptDocumentReference/cbc:ID"),
            despatchAdviceReference = root.text("cac:DespatchDocumentReference/cbc:ID"),
            tenderOrLotReference = root.text("cac:OriginatorDocumentReference/cbc:ID"),
            invoicedObjectIdentifier =
                additionalDocs.firstOrNull { it.text("cbc:DocumentTypeCode") == "130" }?.identifier("cbc:ID"),
            buyerAccountingReference = root.text("cbc:AccountingCost"),
            paymentTerms = root.children("cac:PaymentTerms/cbc:Note").mapNotNull { it.text }.joinOrNull(),
            notes = root.children("cbc:Note").mapNotNull { it.text?.let(::note) },
            businessProcessType = root.text("cbc:ProfileID"),
            specificationIdentifier = root.text("cbc:CustomizationID"),
            precedingInvoices =
                root.children("cac:BillingReference/cac:InvoiceDocumentReference").mapNotNull { ref ->
                    ref.text("cbc:ID")?.let { PrecedingInvoiceReference(it, ref.isoDate("cbc:IssueDate", "BT-26", warnings)) }
                },
            seller = seller(root.child("cac:AccountingSupplierParty/cac:Party")),
            buyer = buyer(root.child("cac:AccountingCustomerParty/cac:Party")),
            payee = root.child("cac:PayeeParty")?.let(::payee),
            sellerTaxRepresentative = root.child("cac:TaxRepresentativeParty")?.let(::taxRepresentative),
            delivery = delivery(),
            paymentInstructions = paymentInstructions(),
            allowances = allowanceCharges(root, charge = false, withVat = true),
            charges = allowanceCharges(root, charge = true, withVat = true),
            totals = totals(currency, taxCurrency),
            vatBreakdown = vatBreakdown(currency),
            supportingDocuments =
                additionalDocs
                    .filter { it.text("cbc:DocumentTypeCode") !in setOf("130", if (isCreditNote) "50" else "-") }
                    .mapNotNull(::supportingDocument),
            lines = root.children(lineTag).map(::line),
            thirdPartyPayments =
                root.children("cac:PrepaidPayment").map {
                    ThirdPartyPayment(it.text("cbc:ID"), it.decimal("cbc:PaidAmount", "BT-DEX-002", warnings), it.text("cbc:InstructionID"))
                },
        )
    }

    private fun note(raw: String): Note {
        // UBL has no element for BT-21; the binding encodes it as a "#CODE#" prefix.
        val match = SUBJECT_CODE.matchEntire(raw)
        return if (match != null) Note(match.groupValues[1], match.groupValues[2].trim()) else Note(null, raw)
    }

    private fun seller(party: El?): Seller {
        if (party == null) return Seller()
        val taxSchemes = party.children("cac:PartyTaxScheme")
        return Seller(
            name = party.text("cac:PartyLegalEntity/cbc:RegistrationName"),
            tradingName = party.text("cac:PartyName/cbc:Name"),
            identifiers =
                party
                    .children("cac:PartyIdentification/cbc:ID")
                    .filter { it.attr("schemeID") != SEPA }
                    .mapNotNull { it.asIdentifier() },
            legalRegistrationId = party.identifier("cac:PartyLegalEntity/cbc:CompanyID"),
            vatId = taxSchemes.firstOrNull { it.text("cac:TaxScheme/cbc:ID") == "VAT" }?.text("cbc:CompanyID"),
            taxRegistrationId = taxSchemes.firstOrNull { it.text("cac:TaxScheme/cbc:ID") != "VAT" }?.text("cbc:CompanyID"),
            additionalLegalInformation = party.text("cac:PartyLegalEntity/cbc:CompanyLegalForm"),
            electronicAddress = party.identifier("cbc:EndpointID"),
            address = party.child("cac:PostalAddress")?.let(::address),
            contact = party.child("cac:Contact")?.let(::contact),
        )
    }

    private fun buyer(party: El?): Buyer {
        if (party == null) return Buyer()
        return Buyer(
            name = party.text("cac:PartyLegalEntity/cbc:RegistrationName"),
            tradingName = party.text("cac:PartyName/cbc:Name"),
            identifier = party.identifier("cac:PartyIdentification/cbc:ID"),
            legalRegistrationId = party.identifier("cac:PartyLegalEntity/cbc:CompanyID"),
            vatId = party.text("cac:PartyTaxScheme/cbc:CompanyID"),
            electronicAddress = party.identifier("cbc:EndpointID"),
            address = party.child("cac:PostalAddress")?.let(::address),
            contact = party.child("cac:Contact")?.let(::contact),
        )
    }

    private fun payee(party: El): Payee =
        Payee(
            name = party.text("cac:PartyName/cbc:Name"),
            identifier =
                party
                    .children("cac:PartyIdentification/cbc:ID")
                    .firstOrNull { it.attr("schemeID") != SEPA }
                    ?.asIdentifier(),
            legalRegistrationId = party.identifier("cac:PartyLegalEntity/cbc:CompanyID"),
        )

    private fun taxRepresentative(party: El): TaxRepresentative =
        TaxRepresentative(
            name = party.text("cac:PartyName/cbc:Name"),
            vatId = party.text("cac:PartyTaxScheme/cbc:CompanyID"),
            address = party.child("cac:PostalAddress")?.let(::address),
        )

    private fun address(el: El): Address =
        Address(
            line1 = el.text("cbc:StreetName"),
            line2 = el.text("cbc:AdditionalStreetName"),
            line3 = el.text("cac:AddressLine/cbc:Line"),
            city = el.text("cbc:CityName"),
            postCode = el.text("cbc:PostalZone"),
            subdivision = el.text("cbc:CountrySubentity"),
            countryCode = el.text("cac:Country/cbc:IdentificationCode"),
        )

    private fun contact(el: El): Contact =
        Contact(
            point = el.text("cbc:Name"),
            phone = el.text("cbc:Telephone"),
            email = el.text("cbc:ElectronicMail"),
        )

    private fun delivery(): Delivery? {
        val d = root.child("cac:Delivery")
        val period = root.child("cac:InvoicePeriod")?.let(::period)
        if (d == null && period == null) return null
        return Delivery(
            deliverToName = d?.text("cac:DeliveryParty/cac:PartyName/cbc:Name"),
            locationIdentifier = d?.identifier("cac:DeliveryLocation/cbc:ID"),
            actualDeliveryDate = d?.isoDate("cbc:ActualDeliveryDate", "BT-72", warnings),
            invoicingPeriod = period,
            deliverToAddress = d?.child("cac:DeliveryLocation/cac:Address")?.let(::address),
        )
    }

    private fun period(el: El): Period? {
        val p = Period(el.isoDate("cbc:StartDate", "BT-73", warnings), el.isoDate("cbc:EndDate", "BT-74", warnings))
        return p.takeIf { it.start != null || it.end != null }
    }

    private fun paymentInstructions(): PaymentInstructions? {
        val means = root.children("cac:PaymentMeans")
        if (means.isEmpty()) return null
        val first = means.first()
        val creditorId =
            (
                root.children("cac:AccountingSupplierParty/cac:Party/cac:PartyIdentification/cbc:ID") +
                    root.children("cac:PayeeParty/cac:PartyIdentification/cbc:ID")
            ).firstOrNull { it.attr("schemeID") == SEPA }?.text
        val mandate = means.firstNotNullOfOrNull { it.child("cac:PaymentMandate") }
        return PaymentInstructions(
            meansTypeCode = first.text("cbc:PaymentMeansCode"),
            meansText = first.child("cbc:PaymentMeansCode")?.attr("name"),
            remittanceInformation = means.firstNotNullOfOrNull { it.text("cbc:PaymentID") },
            creditTransfers =
                means.flatMap { it.children("cac:PayeeFinancialAccount") }.mapNotNull { acc ->
                    acc.text("cbc:ID")?.let {
                        CreditTransfer(it, acc.text("cbc:Name"), acc.text("cac:FinancialInstitutionBranch/cbc:ID"))
                    }
                },
            paymentCard =
                means.firstNotNullOfOrNull { it.child("cac:CardAccount") }?.let { card ->
                    card.text("cbc:PrimaryAccountNumberID")?.let { PaymentCard(it, card.text("cbc:HolderName")) }
                },
            directDebit =
                if (mandate != null || creditorId != null) {
                    DirectDebit(mandate?.text("cbc:ID"), creditorId, mandate?.text("cac:PayerFinancialAccount/cbc:ID"))
                } else {
                    null
                },
        )
    }

    private fun allowanceCharges(
        parent: El,
        charge: Boolean,
        withVat: Boolean,
    ): List<AllowanceCharge> =
        parent
            .children("cac:AllowanceCharge")
            .filter { it.text("cbc:ChargeIndicator")?.lowercase() == charge.toString() }
            .map { ac ->
                AllowanceCharge(
                    amount = ac.decimal("cbc:Amount", if (charge) "BT-99" else "BT-92", warnings),
                    baseAmount = ac.decimal("cbc:BaseAmount", if (charge) "BT-100" else "BT-93", warnings),
                    percentage = ac.decimal("cbc:MultiplierFactorNumeric", if (charge) "BT-101" else "BT-94", warnings),
                    vatCategoryCode = if (withVat) ac.text("cac:TaxCategory/cbc:ID") else null,
                    vatRate = if (withVat) ac.decimal("cac:TaxCategory/cbc:Percent", if (charge) "BT-103" else "BT-96", warnings) else null,
                    reason = ac.text("cbc:AllowanceChargeReason"),
                    reasonCode = ac.text("cbc:AllowanceChargeReasonCode"),
                )
            }

    private fun totals(
        currency: String?,
        taxCurrency: String?,
    ): DocumentTotals {
        val m = root.child("cac:LegalMonetaryTotal")
        val taxTotals = root.children("cac:TaxTotal/cbc:TaxAmount")

        fun taxIn(cur: String?) = cur?.let { c -> taxTotals.firstOrNull { it.attr("currencyID") == c } }?.decimal("BT-110", warnings)
        return DocumentTotals(
            lineNetTotal = m?.decimal("cbc:LineExtensionAmount", "BT-106", warnings),
            allowanceTotal = m?.decimal("cbc:AllowanceTotalAmount", "BT-107", warnings),
            chargeTotal = m?.decimal("cbc:ChargeTotalAmount", "BT-108", warnings),
            totalWithoutVat = m?.decimal("cbc:TaxExclusiveAmount", "BT-109", warnings),
            totalVat = taxIn(currency) ?: if (taxCurrency == null) taxTotals.firstOrNull()?.decimal("BT-110", warnings) else null,
            totalVatInAccountingCurrency = if (taxCurrency != null && taxCurrency != currency) taxIn(taxCurrency) else null,
            totalWithVat = m?.decimal("cbc:TaxInclusiveAmount", "BT-112", warnings),
            paidAmount = m?.decimal("cbc:PrepaidAmount", "BT-113", warnings),
            roundingAmount = m?.decimal("cbc:PayableRoundingAmount", "BT-114", warnings),
            amountDue = m?.decimal("cbc:PayableAmount", "BT-115", warnings),
        )
    }

    private fun vatBreakdown(currency: String?): List<VatBreakdown> {
        val totals = root.children("cac:TaxTotal")
        val main =
            totals.firstOrNull { it.child("cac:TaxSubtotal") != null && it.child("cbc:TaxAmount")?.attr("currencyID") == currency }
                ?: totals.firstOrNull { it.child("cac:TaxSubtotal") != null }
                ?: return emptyList()
        return main.children("cac:TaxSubtotal").map { st ->
            VatBreakdown(
                taxableAmount = st.decimal("cbc:TaxableAmount", "BT-116", warnings),
                taxAmount = st.decimal("cbc:TaxAmount", "BT-117", warnings),
                categoryCode = st.text("cac:TaxCategory/cbc:ID"),
                rate = st.decimal("cac:TaxCategory/cbc:Percent", "BT-119", warnings),
                exemptionReason = st.text("cac:TaxCategory/cbc:TaxExemptionReason"),
                exemptionReasonCode = st.text("cac:TaxCategory/cbc:TaxExemptionReasonCode"),
            )
        }
    }

    private fun supportingDocument(ref: El): SupportingDocument? {
        val id = ref.text("cbc:ID") ?: return null
        val binary = ref.child("cac:Attachment/cbc:EmbeddedDocumentBinaryObject")
        return SupportingDocument(
            reference = id,
            description = ref.text("cbc:DocumentDescription"),
            externalLocation = ref.text("cac:Attachment/cac:ExternalReference/cbc:URI"),
            attachment = binary?.let { attachment(it, warnings) },
        )
    }

    private fun line(el: El): InvoiceLine {
        val price = el.child("cac:Price")
        val priceAllowance = price?.child("cac:AllowanceCharge")
        val item = el.child("cac:Item")
        return InvoiceLine(
            id = el.text("cbc:ID"),
            note = el.children("cbc:Note").mapNotNull { it.text }.joinOrNull(),
            objectIdentifier =
                el.children("cac:DocumentReference").firstOrNull { it.text("cbc:DocumentTypeCode") == "130" }?.identifier("cbc:ID"),
            quantity = el.decimal(quantityTag, "BT-129", warnings),
            unitCode = el.child(quantityTag)?.attr("unitCode"),
            netAmount = el.decimal("cbc:LineExtensionAmount", "BT-131", warnings),
            orderLineReference = el.text("cac:OrderLineReference/cbc:LineID"),
            buyerAccountingReference = el.text("cbc:AccountingCost"),
            period =
                el.child("cac:InvoicePeriod")?.let {
                    Period(it.isoDate("cbc:StartDate", "BT-134", warnings), it.isoDate("cbc:EndDate", "BT-135", warnings))
                        .takeIf { p -> p.start != null || p.end != null }
                },
            allowances = allowanceCharges(el, charge = false, withVat = false),
            charges = allowanceCharges(el, charge = true, withVat = false),
            price =
                PriceDetails(
                    netPrice = price?.decimal("cbc:PriceAmount", "BT-146", warnings),
                    discount = priceAllowance?.decimal("cbc:Amount", "BT-147", warnings),
                    grossPrice = priceAllowance?.decimal("cbc:BaseAmount", "BT-148", warnings),
                    baseQuantity = price?.decimal("cbc:BaseQuantity", "BT-149", warnings),
                    baseQuantityUnitCode = price?.child("cbc:BaseQuantity")?.attr("unitCode"),
                ),
            vatCategoryCode = item?.text("cac:ClassifiedTaxCategory/cbc:ID"),
            vatRate = item?.decimal("cac:ClassifiedTaxCategory/cbc:Percent", "BT-152", warnings),
            item =
                Item(
                    name = item?.text("cbc:Name"),
                    description = item?.text("cbc:Description"),
                    sellerId = item?.text("cac:SellersItemIdentification/cbc:ID"),
                    buyerId = item?.text("cac:BuyersItemIdentification/cbc:ID"),
                    standardId = item?.identifier("cac:StandardItemIdentification/cbc:ID"),
                    classifications =
                        item?.children("cac:CommodityClassification/cbc:ItemClassificationCode")?.mapNotNull {
                            it.asIdentifier("listID", "listVersionID")
                        } ?: emptyList(),
                    originCountry = item?.text("cac:OriginCountry/cbc:IdentificationCode"),
                    attributes =
                        item?.children("cac:AdditionalItemProperty")?.mapNotNull { p ->
                            val name = p.text("cbc:Name")
                            val value = p.text("cbc:Value")
                            if (name != null && value != null) ItemAttribute(name, value) else null
                        } ?: emptyList(),
                ),
            subLines = el.children("cac:SubInvoiceLine").map(::line),
        )
    }

    private companion object {
        const val SEPA = "SEPA"
        val SUBJECT_CODE = Regex("^#([A-Z]{3})#(.*)$", RegexOption.DOT_MATCHES_ALL)
    }
}

internal fun List<String>.joinOrNull(): String? = if (isEmpty()) null else joinToString("\n")

/** Decodes a Base64 `EmbeddedDocumentBinaryObject` / `AttachmentBinaryObject`. */
internal fun attachment(
    el: El,
    warnings: Warnings,
): Attachment? {
    val raw = el.text ?: return null
    return try {
        Attachment(el.attr("mimeCode"), el.attr("filename"), Base64.getMimeDecoder().decode(raw))
    } catch (_: IllegalArgumentException) {
        warnings.add("INVALID_BASE64", "BT-125", "Attachment content is not valid Base64")
        null
    }
}

internal fun identifierOf(
    value: String?,
    scheme: String?,
): Identifier? = value?.let { Identifier(it, scheme) }
