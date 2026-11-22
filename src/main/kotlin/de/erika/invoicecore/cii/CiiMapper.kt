package de.erika.invoicecore.cii

import de.erika.invoicecore.model.Address
import de.erika.invoicecore.model.AllowanceCharge
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
import de.erika.invoicecore.model.VatBreakdown
import de.erika.invoicecore.ubl.attachment
import de.erika.invoicecore.ubl.joinOrNull
import de.erika.invoicecore.xml.El
import de.erika.invoicecore.xml.Warnings

/**
 * Maps a UN/CEFACT CII D16B `CrossIndustryInvoice` (XRechnung CII, ZUGFeRD 2.x,
 * Factur-X) to the neutral model, following the EN 16931-3-3 syntax binding.
 */
internal class CiiMapper(
    private val root: El,
    private val warnings: Warnings,
) {
    private val transaction = root.child("rsm:SupplyChainTradeTransaction")
    private val agreement = transaction?.child("ram:ApplicableHeaderTradeAgreement")
    private val deliveryEl = transaction?.child("ram:ApplicableHeaderTradeDelivery")
    private val settlement = transaction?.child("ram:ApplicableHeaderTradeSettlement")
    private val lineEls = transaction?.children("ram:IncludedSupplyChainTradeLineItem") ?: emptyList()

    fun lineCount(): Int = lineEls.size

    fun map(): Invoice {
        val doc = root.child("rsm:ExchangedDocument")
        val context = root.child("rsm:ExchangedDocumentContext")
        val currency = settlement?.text("ram:InvoiceCurrencyCode")
        val taxCurrency = settlement?.text("ram:TaxCurrencyCode")
        val additionalDocs = agreement?.children("ram:AdditionalReferencedDocument") ?: emptyList()
        val terms = settlement?.children("ram:SpecifiedTradePaymentTerms") ?: emptyList()
        val headerTaxes = settlement?.children("ram:ApplicableTradeTax") ?: emptyList()
        return Invoice(
            number = doc?.text("ram:ID"),
            issueDate = doc?.ciiDate("ram:IssueDateTime/udt:DateTimeString", "BT-2", warnings),
            typeCode = doc?.text("ram:TypeCode"),
            currency = currency,
            vatAccountingCurrency = taxCurrency,
            taxPointDate = headerTaxes.firstNotNullOfOrNull { it.ciiDate("ram:TaxPointDate/udt:DateString", "BT-7", warnings) },
            vatPointDateCode = headerTaxes.firstNotNullOfOrNull { it.text("ram:DueDateTypeCode") }?.let(::toUntdid2005),
            dueDate = terms.firstNotNullOfOrNull { it.ciiDate("ram:DueDateDateTime/udt:DateTimeString", "BT-9", warnings) },
            buyerReference = agreement?.text("ram:BuyerReference"),
            projectReference = agreement?.text("ram:SpecifiedProcuringProject/ram:ID"),
            contractReference = agreement?.text("ram:ContractReferencedDocument/ram:IssuerAssignedID"),
            purchaseOrderReference = agreement?.text("ram:BuyerOrderReferencedDocument/ram:IssuerAssignedID"),
            salesOrderReference = agreement?.text("ram:SellerOrderReferencedDocument/ram:IssuerAssignedID"),
            receivingAdviceReference = deliveryEl?.text("ram:ReceivingAdviceReferencedDocument/ram:IssuerAssignedID"),
            despatchAdviceReference = deliveryEl?.text("ram:DespatchAdviceReferencedDocument/ram:IssuerAssignedID"),
            tenderOrLotReference = additionalDocs.firstOrNull { it.text("ram:TypeCode") == "50" }?.text("ram:IssuerAssignedID"),
            invoicedObjectIdentifier =
                additionalDocs.firstOrNull { it.text("ram:TypeCode") == "130" }?.let { objectIdentifier(it) },
            buyerAccountingReference = settlement?.text("ram:ReceivableSpecifiedTradeAccountingAccount/ram:ID"),
            paymentTerms = terms.mapNotNull { it.text("ram:Description") }.joinOrNull(),
            notes =
                doc?.children("ram:IncludedNote")?.mapNotNull { n ->
                    n
                        .children("ram:Content")
                        .mapNotNull { it.text }
                        .joinOrNull()
                        ?.let { Note(n.text("ram:SubjectCode"), it) }
                } ?: emptyList(),
            businessProcessType = context?.text("ram:BusinessProcessSpecifiedDocumentContextParameter/ram:ID"),
            specificationIdentifier = context?.text("ram:GuidelineSpecifiedDocumentContextParameter/ram:ID"),
            precedingInvoices =
                settlement?.children("ram:InvoiceReferencedDocument")?.mapNotNull { ref ->
                    ref.text("ram:IssuerAssignedID")?.let {
                        PrecedingInvoiceReference(it, ref.ciiDate("ram:FormattedIssueDateTime/qdt:DateTimeString", "BT-26", warnings))
                    }
                } ?: emptyList(),
            seller = seller(agreement?.child("ram:SellerTradeParty")),
            buyer = buyer(agreement?.child("ram:BuyerTradeParty")),
            payee = settlement?.child("ram:PayeeTradeParty")?.let(::payee),
            sellerTaxRepresentative = agreement?.child("ram:SellerTaxRepresentativeTradeParty")?.let(::taxRepresentative),
            delivery = delivery(),
            paymentInstructions = paymentInstructions(terms),
            allowances = settlement?.let { allowanceCharges(it, charge = false, header = true) } ?: emptyList(),
            charges = settlement?.let { allowanceCharges(it, charge = true, header = true) } ?: emptyList(),
            totals = totals(currency, taxCurrency),
            vatBreakdown =
                headerTaxes.map { t ->
                    VatBreakdown(
                        taxableAmount = t.decimal("ram:BasisAmount", "BT-116", warnings),
                        taxAmount = t.decimal("ram:CalculatedAmount", "BT-117", warnings),
                        categoryCode = t.text("ram:CategoryCode"),
                        rate = t.decimal("ram:RateApplicablePercent", "BT-119", warnings),
                        exemptionReason = t.text("ram:ExemptionReason"),
                        exemptionReasonCode = t.text("ram:ExemptionReasonCode"),
                    )
                },
            supportingDocuments = additionalDocs.filter { it.text("ram:TypeCode") == "916" }.mapNotNull(::supportingDocument),
            lines = nestLines(lineEls.map { it to line(it) }),
        )
    }

    /**
     * BT-8 uses UNTDID 2005 in the semantic model and UBL, but UNTDID 2475 in CII
     * (EN 16931-3-3). Normalise to 2005: 5 → 3 (invoice date), 29 → 35 (delivery date),
     * 72 → 432 (paid to date).
     */
    private fun toUntdid2005(code: String): String =
        when (code) {
            "5" -> "3"
            "29" -> "35"
            "72" -> "432"
            else -> code
        }

    /** XRechnung CII extension: lines with `ram:ParentLineID` become sub lines of their parent. */
    private fun nestLines(lines: List<Pair<El, InvoiceLine>>): List<InvoiceLine> {
        val parentOf = lines.associate { (el, line) -> line.id to el.text("ram:AssociatedDocumentLineDocument/ram:ParentLineID") }
        if (parentOf.values.all { it == null }) return lines.map { it.second }
        val byParent = lines.map { it.second }.groupBy { parentOf[it.id] }

        fun attach(line: InvoiceLine): InvoiceLine = line.copy(subLines = (byParent[line.id] ?: emptyList()).map(::attach))
        return (byParent[null] ?: emptyList()).map(::attach)
    }

    private fun partyIdentifiers(party: El): List<Identifier> =
        party.children("ram:ID").mapNotNull { it.asIdentifier() } + party.children("ram:GlobalID").mapNotNull { it.asIdentifier() }

    private fun taxRegistration(
        party: El,
        scheme: String,
    ): String? = party.children("ram:SpecifiedTaxRegistration/ram:ID").firstOrNull { it.attr("schemeID") == scheme }?.text

    private fun seller(party: El?): Seller {
        if (party == null) return Seller()
        return Seller(
            name = party.text("ram:Name"),
            tradingName = party.text("ram:SpecifiedLegalOrganization/ram:TradingBusinessName"),
            identifiers = partyIdentifiers(party),
            legalRegistrationId = party.identifier("ram:SpecifiedLegalOrganization/ram:ID"),
            vatId = taxRegistration(party, "VA"),
            taxRegistrationId = taxRegistration(party, "FC"),
            additionalLegalInformation = party.text("ram:Description"),
            electronicAddress = party.identifier("ram:URIUniversalCommunication/ram:URIID"),
            address = party.child("ram:PostalTradeAddress")?.let(::address),
            contact = party.child("ram:DefinedTradeContact")?.let(::contact),
        )
    }

    private fun buyer(party: El?): Buyer {
        if (party == null) return Buyer()
        return Buyer(
            name = party.text("ram:Name"),
            tradingName = party.text("ram:SpecifiedLegalOrganization/ram:TradingBusinessName"),
            identifier = partyIdentifiers(party).firstOrNull(),
            legalRegistrationId = party.identifier("ram:SpecifiedLegalOrganization/ram:ID"),
            vatId = taxRegistration(party, "VA"),
            electronicAddress = party.identifier("ram:URIUniversalCommunication/ram:URIID"),
            address = party.child("ram:PostalTradeAddress")?.let(::address),
            contact = party.child("ram:DefinedTradeContact")?.let(::contact),
        )
    }

    private fun payee(party: El): Payee =
        Payee(
            name = party.text("ram:Name"),
            identifier = partyIdentifiers(party).firstOrNull(),
            legalRegistrationId = party.identifier("ram:SpecifiedLegalOrganization/ram:ID"),
        )

    private fun taxRepresentative(party: El): TaxRepresentative =
        TaxRepresentative(
            name = party.text("ram:Name"),
            vatId = taxRegistration(party, "VA"),
            address = party.child("ram:PostalTradeAddress")?.let(::address),
        )

    private fun address(el: El): Address =
        Address(
            line1 = el.text("ram:LineOne"),
            line2 = el.text("ram:LineTwo"),
            line3 = el.text("ram:LineThree"),
            city = el.text("ram:CityName"),
            postCode = el.text("ram:PostcodeCode"),
            subdivision = el.text("ram:CountrySubDivisionName"),
            countryCode = el.text("ram:CountryID"),
        )

    private fun contact(el: El): Contact =
        Contact(
            point = el.text("ram:PersonName") ?: el.text("ram:DepartmentName"),
            phone = el.text("ram:TelephoneUniversalCommunication/ram:CompleteNumber"),
            email = el.text("ram:EmailURIUniversalCommunication/ram:URIID"),
        )

    private fun delivery(): Delivery? {
        val shipTo = deliveryEl?.child("ram:ShipToTradeParty")
        val date = deliveryEl?.ciiDate("ram:ActualDeliverySupplyChainEvent/ram:OccurrenceDateTime/udt:DateTimeString", "BT-72", warnings)
        val period = settlement?.child("ram:BillingSpecifiedPeriod")?.let { period(it, "BT-73", "BT-74") }
        if (shipTo == null && date == null && period == null) return null
        return Delivery(
            deliverToName = shipTo?.text("ram:Name"),
            locationIdentifier = shipTo?.let { partyIdentifiers(it).firstOrNull() },
            actualDeliveryDate = date,
            invoicingPeriod = period,
            deliverToAddress = shipTo?.child("ram:PostalTradeAddress")?.let(::address),
        )
    }

    private fun period(
        el: El,
        startBt: String,
        endBt: String,
    ): Period? {
        val p =
            Period(
                el.ciiDate("ram:StartDateTime/udt:DateTimeString", startBt, warnings),
                el.ciiDate("ram:EndDateTime/udt:DateTimeString", endBt, warnings),
            )
        return p.takeIf { it.start != null || it.end != null }
    }

    private fun paymentInstructions(terms: List<El>): PaymentInstructions? {
        val s = settlement ?: return null
        val means = s.children("ram:SpecifiedTradeSettlementPaymentMeans")
        val creditorId = s.text("ram:CreditorReferenceID")
        val mandate = terms.firstNotNullOfOrNull { it.text("ram:DirectDebitMandateID") }
        val debited = means.firstNotNullOfOrNull { it.text("ram:PayerPartyDebtorFinancialAccount/ram:IBANID") }
        val remittance = s.text("ram:PaymentReference")
        if (means.isEmpty() && remittance == null && creditorId == null && mandate == null) return null
        return PaymentInstructions(
            meansTypeCode = means.firstNotNullOfOrNull { it.text("ram:TypeCode") },
            meansText = means.firstNotNullOfOrNull { it.text("ram:Information") },
            remittanceInformation = remittance,
            creditTransfers =
                means.flatMap { m ->
                    val bic = m.text("ram:PayeeSpecifiedCreditorFinancialInstitution/ram:BICID")
                    m.children("ram:PayeePartyCreditorFinancialAccount").mapNotNull { acc ->
                        (
                            acc.text(
                                "ram:IBANID",
                            ) ?: acc.text("ram:ProprietaryID")
                        )?.let { CreditTransfer(it, acc.text("ram:AccountName"), bic) }
                    }
                },
            paymentCard =
                means.firstNotNullOfOrNull { it.child("ram:ApplicableTradeSettlementFinancialCard") }?.let { card ->
                    card.text("ram:ID")?.let { PaymentCard(it, card.text("ram:CardholderName")) }
                },
            directDebit =
                if (mandate != null || creditorId != null || debited != null) DirectDebit(mandate, creditorId, debited) else null,
        )
    }

    private fun allowanceCharges(
        parent: El,
        charge: Boolean,
        header: Boolean,
    ): List<AllowanceCharge> =
        parent
            .children("ram:SpecifiedTradeAllowanceCharge")
            .filter { it.text("ram:ChargeIndicator/udt:Indicator")?.lowercase() == charge.toString() }
            .map { ac ->
                AllowanceCharge(
                    amount = ac.decimal("ram:ActualAmount", if (charge) "BT-99" else "BT-92", warnings),
                    baseAmount = ac.decimal("ram:BasisAmount", if (charge) "BT-100" else "BT-93", warnings),
                    percentage = ac.decimal("ram:CalculationPercent", if (charge) "BT-101" else "BT-94", warnings),
                    vatCategoryCode = if (header) ac.text("ram:CategoryTradeTax/ram:CategoryCode") else null,
                    vatRate = if (header) ac.decimal("ram:CategoryTradeTax/ram:RateApplicablePercent", "BT-96", warnings) else null,
                    reason = ac.text("ram:Reason"),
                    reasonCode = ac.text("ram:ReasonCode"),
                )
            }

    private fun totals(
        currency: String?,
        taxCurrency: String?,
    ): DocumentTotals {
        val m = settlement?.child("ram:SpecifiedTradeSettlementHeaderMonetarySummation") ?: return DocumentTotals()
        val taxTotals = m.children("ram:TaxTotalAmount")

        fun taxIn(cur: String?) = cur?.let { c -> taxTotals.firstOrNull { it.attr("currencyID") == c } }?.decimal("BT-110", warnings)
        return DocumentTotals(
            lineNetTotal = m.decimal("ram:LineTotalAmount", "BT-106", warnings),
            allowanceTotal = m.decimal("ram:AllowanceTotalAmount", "BT-107", warnings),
            chargeTotal = m.decimal("ram:ChargeTotalAmount", "BT-108", warnings),
            totalWithoutVat = m.decimal("ram:TaxBasisTotalAmount", "BT-109", warnings),
            totalVat =
                taxIn(currency)
                    ?: taxTotals.firstOrNull { it.attr("currencyID") == null || taxCurrency == null }?.decimal("BT-110", warnings),
            totalVatInAccountingCurrency = if (taxCurrency != null && taxCurrency != currency) taxIn(taxCurrency) else null,
            totalWithVat = m.decimal("ram:GrandTotalAmount", "BT-112", warnings),
            paidAmount = m.decimal("ram:TotalPrepaidAmount", "BT-113", warnings),
            roundingAmount = m.decimal("ram:RoundingAmount", "BT-114", warnings),
            amountDue = m.decimal("ram:DuePayableAmount", "BT-115", warnings),
        )
    }

    private fun supportingDocument(ref: El): SupportingDocument? {
        val id = ref.text("ram:IssuerAssignedID") ?: return null
        return SupportingDocument(
            reference = id,
            description = ref.text("ram:Name"),
            externalLocation = ref.text("ram:URIID"),
            attachment = ref.child("ram:AttachmentBinaryObject")?.let { attachment(it, warnings) },
        )
    }

    private fun objectIdentifier(ref: El): Identifier? =
        ref.text("ram:IssuerAssignedID")?.let { Identifier(it, ref.text("ram:ReferenceTypeCode")) }

    private fun line(el: El): InvoiceLine {
        val doc = el.child("ram:AssociatedDocumentLineDocument")
        val product = el.child("ram:SpecifiedTradeProduct")
        val lineAgreement = el.child("ram:SpecifiedLineTradeAgreement")
        val lineSettlement = el.child("ram:SpecifiedLineTradeSettlement")
        val gross = lineAgreement?.child("ram:GrossPriceProductTradePrice")
        val net = lineAgreement?.child("ram:NetPriceProductTradePrice")
        val baseQty = net?.child("ram:BasisQuantity") ?: gross?.child("ram:BasisQuantity")
        val tax = lineSettlement?.child("ram:ApplicableTradeTax")
        val billed = el.child("ram:SpecifiedLineTradeDelivery/ram:BilledQuantity")
        return InvoiceLine(
            id = doc?.text("ram:LineID"),
            note = doc?.children("ram:IncludedNote/ram:Content")?.mapNotNull { it.text }?.joinOrNull(),
            objectIdentifier =
                lineSettlement?.children("ram:AdditionalReferencedDocument")?.firstOrNull { it.text("ram:TypeCode") == "130" }?.let {
                    objectIdentifier(it)
                },
            quantity = billed?.decimal("BT-129", warnings),
            unitCode = billed?.attr("unitCode"),
            netAmount =
                lineSettlement?.decimal(
                    "ram:SpecifiedTradeSettlementLineMonetarySummation/ram:LineTotalAmount",
                    "BT-131",
                    warnings,
                ),
            orderLineReference = lineAgreement?.text("ram:BuyerOrderReferencedDocument/ram:LineID"),
            buyerAccountingReference = lineSettlement?.text("ram:ReceivableSpecifiedTradeAccountingAccount/ram:ID"),
            period = lineSettlement?.child("ram:BillingSpecifiedPeriod")?.let { period(it, "BT-134", "BT-135") },
            allowances = lineSettlement?.let { allowanceCharges(it, charge = false, header = false) } ?: emptyList(),
            charges = lineSettlement?.let { allowanceCharges(it, charge = true, header = false) } ?: emptyList(),
            price =
                PriceDetails(
                    netPrice = net?.decimal("ram:ChargeAmount", "BT-146", warnings),
                    discount =
                        gross
                            ?.children("ram:AppliedTradeAllowanceCharge")
                            ?.firstOrNull { it.text("ram:ChargeIndicator/udt:Indicator")?.lowercase() != "true" }
                            ?.decimal("ram:ActualAmount", "BT-147", warnings),
                    grossPrice = gross?.decimal("ram:ChargeAmount", "BT-148", warnings),
                    baseQuantity = baseQty?.decimal("BT-149", warnings),
                    baseQuantityUnitCode = baseQty?.attr("unitCode"),
                ),
            vatCategoryCode = tax?.text("ram:CategoryCode"),
            vatRate = tax?.decimal("ram:RateApplicablePercent", "BT-152", warnings),
            item =
                Item(
                    name = product?.text("ram:Name"),
                    description = product?.text("ram:Description"),
                    sellerId = product?.text("ram:SellerAssignedID"),
                    buyerId = product?.text("ram:BuyerAssignedID"),
                    standardId = product?.identifier("ram:GlobalID"),
                    classifications =
                        product?.children("ram:DesignatedProductClassification/ram:ClassCode")?.mapNotNull {
                            it.asIdentifier("listID", "listVersionID")
                        } ?: emptyList(),
                    originCountry = product?.text("ram:OriginTradeCountry/ram:ID"),
                    attributes =
                        product?.children("ram:ApplicableProductCharacteristic")?.mapNotNull { c ->
                            val name = c.text("ram:Description")
                            val value = c.text("ram:Value")
                            if (name != null && value != null) ItemAttribute(name, value) else null
                        } ?: emptyList(),
                ),
        )
    }
}
