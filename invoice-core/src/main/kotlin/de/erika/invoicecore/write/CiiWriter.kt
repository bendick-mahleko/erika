package de.erika.invoicecore.write

import de.erika.invoicecore.model.Address
import de.erika.invoicecore.model.AllowanceCharge
import de.erika.invoicecore.model.Contact
import de.erika.invoicecore.model.Identifier
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.InvoiceLine
import de.erika.invoicecore.model.Period
import de.erika.invoicecore.write.XmlOut.Companion.basic
import de.erika.invoicecore.xml.Ns

/**
 * Writes the neutral model as UN/CEFACT CII D16B (`rsm:CrossIndustryInvoice`), following the
 * EN 16931-3-3 binding and the element order of the D16B schema. Used for XRechnung CII and
 * for the XML embedded in a ZUGFeRD/Factur-X PDF; only BT-24 differs.
 *
 * Writes the business terms an invoice creator produces (see [InvoiceWriter]); the mapping
 * is the inverse of [de.erika.invoicecore.cii.CiiMapper], which the round-trip tests check.
 */
internal object CiiWriter {
    fun write(invoice: Invoice): ByteArray {
        val x = XmlOut("rsm:CrossIndustryInvoice", listOf("rsm" to Ns.RSM, "ram" to Ns.RAM, "qdt" to Ns.QDT, "udt" to Ns.UDT))
        val currency = invoice.currency
        x.required("rsm:ExchangedDocumentContext") {
            el("ram:BusinessProcessSpecifiedDocumentContextParameter") { text("ram:ID", invoice.businessProcessType) }
            el("ram:GuidelineSpecifiedDocumentContextParameter") { text("ram:ID", invoice.specificationIdentifier) }
        }
        x.el("rsm:ExchangedDocument") {
            text("ram:ID", invoice.number)
            text("ram:TypeCode", invoice.typeCode)
            date("ram:IssueDateTime", invoice.issueDate)
            invoice.notes.forEach { n ->
                el("ram:IncludedNote") {
                    text("ram:Content", n.text)
                    text("ram:SubjectCode", n.subjectCode)
                }
            }
        }
        x.el("rsm:SupplyChainTradeTransaction") {
            invoice.lines.forEach { line(it) }
            required("ram:ApplicableHeaderTradeAgreement") {
                text("ram:BuyerReference", invoice.buyerReference)
                el("ram:SellerTradeParty") {
                    val s = invoice.seller
                    identifiers(s.identifiers)
                    text("ram:Name", s.name)
                    text("ram:Description", s.additionalLegalInformation)
                    legalOrganization(s.legalRegistrationId, s.tradingName)
                    contact(s.contact)
                    address(s.address)
                    electronicAddress(s.electronicAddress)
                    taxRegistration("VA", s.vatId)
                    taxRegistration("FC", s.taxRegistrationId)
                }
                el("ram:BuyerTradeParty") {
                    val b = invoice.buyer
                    identifiers(listOfNotNull(b.identifier))
                    text("ram:Name", b.name)
                    legalOrganization(b.legalRegistrationId, b.tradingName)
                    contact(b.contact)
                    address(b.address)
                    electronicAddress(b.electronicAddress)
                    taxRegistration("VA", b.vatId)
                }
                el("ram:SellerOrderReferencedDocument") { text("ram:IssuerAssignedID", invoice.salesOrderReference) }
                el("ram:BuyerOrderReferencedDocument") { text("ram:IssuerAssignedID", invoice.purchaseOrderReference) }
                el("ram:ContractReferencedDocument") { text("ram:IssuerAssignedID", invoice.contractReference) }
            }
            required("ram:ApplicableHeaderTradeDelivery") {
                invoice.delivery?.let { d ->
                    el("ram:ShipToTradeParty") {
                        d.locationIdentifier?.let { identifiers(listOf(it)) }
                        text("ram:Name", d.deliverToName)
                        address(d.deliverToAddress)
                    }
                    el("ram:ActualDeliverySupplyChainEvent") { date("ram:OccurrenceDateTime", d.actualDeliveryDate) }
                }
            }
            required("ram:ApplicableHeaderTradeSettlement") {
                val p = invoice.paymentInstructions
                text("ram:CreditorReferenceID", p?.directDebit?.creditorId)
                text("ram:PaymentReference", p?.remittanceInformation)
                text("ram:InvoiceCurrencyCode", currency)
                p?.let { pay ->
                    val transfers = pay.creditTransfers.ifEmpty { listOf(null) }
                    transfers.forEach { ct ->
                        el("ram:SpecifiedTradeSettlementPaymentMeans") {
                            text("ram:TypeCode", pay.meansTypeCode)
                            text("ram:Information", pay.meansText)
                            el("ram:PayerPartyDebtorFinancialAccount") { text("ram:IBANID", pay.directDebit?.debitedAccountId) }
                            if (ct != null) {
                                el("ram:PayeePartyCreditorFinancialAccount") {
                                    text("ram:IBANID", ct.accountId)
                                    text("ram:AccountName", ct.accountName)
                                }
                                el("ram:PayeeSpecifiedCreditorFinancialInstitution") { text("ram:BICID", ct.serviceProviderId) }
                            }
                        }
                    }
                }
                invoice.vatBreakdown.forEach { v ->
                    el("ram:ApplicableTradeTax") {
                        amount("ram:CalculatedAmount", v.taxAmount)
                        text("ram:TypeCode", "VAT")
                        text("ram:ExemptionReason", v.exemptionReason)
                        amount("ram:BasisAmount", v.taxableAmount)
                        text("ram:CategoryCode", v.categoryCode)
                        text("ram:ExemptionReasonCode", v.exemptionReasonCode)
                        amount("ram:RateApplicablePercent", v.rate)
                    }
                }
                period(invoice.delivery?.invoicingPeriod)
                invoice.charges.forEach { allowanceCharge(it, charge = true, header = true) }
                invoice.allowances.forEach { allowanceCharge(it, charge = false, header = true) }
                if (invoice.paymentTerms != null || invoice.dueDate != null || p?.directDebit?.mandateReference != null) {
                    el("ram:SpecifiedTradePaymentTerms") {
                        text("ram:Description", invoice.paymentTerms)
                        date("ram:DueDateDateTime", invoice.dueDate)
                        text("ram:DirectDebitMandateID", p?.directDebit?.mandateReference)
                    }
                }
                val t = invoice.totals
                el("ram:SpecifiedTradeSettlementHeaderMonetarySummation") {
                    amount("ram:LineTotalAmount", t.lineNetTotal)
                    amount("ram:ChargeTotalAmount", t.chargeTotal)
                    amount("ram:AllowanceTotalAmount", t.allowanceTotal)
                    amount("ram:TaxBasisTotalAmount", t.totalWithoutVat)
                    amount("ram:TaxTotalAmount", t.totalVat, "currencyID" to currency)
                    amount("ram:RoundingAmount", t.roundingAmount)
                    amount("ram:GrandTotalAmount", t.totalWithVat)
                    amount("ram:TotalPrepaidAmount", t.paidAmount)
                    amount("ram:DuePayableAmount", t.amountDue)
                }
                // CII D16B carries one preceding invoice reference (BG-3).
                invoice.precedingInvoices.firstOrNull()?.let { ref ->
                    el("ram:InvoiceReferencedDocument") {
                        text("ram:IssuerAssignedID", ref.number)
                        ref.issueDate?.let {
                            el("ram:FormattedIssueDateTime") { text("qdt:DateTimeString", basic(it), "format" to "102") }
                        }
                    }
                }
                el("ram:ReceivableSpecifiedTradeAccountingAccount") { text("ram:ID", invoice.buyerAccountingReference) }
            }
        }
        return x.finish()
    }

    private fun XmlOut.line(line: InvoiceLine) {
        el("ram:IncludedSupplyChainTradeLineItem") {
            el("ram:AssociatedDocumentLineDocument") {
                text("ram:LineID", line.id)
                el("ram:IncludedNote") { text("ram:Content", line.note) }
            }
            el("ram:SpecifiedTradeProduct") {
                text("ram:SellerAssignedID", line.item.sellerId)
                text("ram:BuyerAssignedID", line.item.buyerId)
                text("ram:Name", line.item.name)
                text("ram:Description", line.item.description)
            }
            el("ram:SpecifiedLineTradeAgreement") {
                el("ram:BuyerOrderReferencedDocument") { text("ram:LineID", line.orderLineReference) }
                el("ram:NetPriceProductTradePrice") {
                    amount("ram:ChargeAmount", line.price.netPrice)
                    amount("ram:BasisQuantity", line.price.baseQuantity, "unitCode" to line.price.baseQuantityUnitCode)
                }
            }
            el("ram:SpecifiedLineTradeDelivery") { amount("ram:BilledQuantity", line.quantity, "unitCode" to line.unitCode) }
            el("ram:SpecifiedLineTradeSettlement") {
                el("ram:ApplicableTradeTax") {
                    text("ram:TypeCode", "VAT")
                    text("ram:CategoryCode", line.vatCategoryCode)
                    amount("ram:RateApplicablePercent", line.vatRate)
                }
                period(line.period)
                line.charges.forEach { allowanceCharge(it, charge = true, header = false) }
                line.allowances.forEach { allowanceCharge(it, charge = false, header = false) }
                el("ram:SpecifiedTradeSettlementLineMonetarySummation") { amount("ram:LineTotalAmount", line.netAmount) }
                el("ram:ReceivableSpecifiedTradeAccountingAccount") { text("ram:ID", line.buyerAccountingReference) }
            }
        }
    }

    private fun XmlOut.allowanceCharge(
        ac: AllowanceCharge,
        charge: Boolean,
        header: Boolean,
    ) {
        el("ram:SpecifiedTradeAllowanceCharge") {
            el("ram:ChargeIndicator") { text("udt:Indicator", charge.toString()) }
            amount("ram:CalculationPercent", ac.percentage)
            amount("ram:BasisAmount", ac.baseAmount)
            amount("ram:ActualAmount", ac.amount)
            text("ram:ReasonCode", ac.reasonCode)
            text("ram:Reason", ac.reason)
            if (header) {
                el("ram:CategoryTradeTax") {
                    text("ram:TypeCode", "VAT")
                    text("ram:CategoryCode", ac.vatCategoryCode)
                    amount("ram:RateApplicablePercent", ac.vatRate)
                }
            }
        }
    }

    private fun XmlOut.date(
        name: String,
        date: java.time.LocalDate?,
    ) {
        if (date == null) return
        el(name) { text("udt:DateTimeString", basic(date), "format" to "102") }
    }

    private fun XmlOut.period(p: Period?) {
        if (p == null) return
        el("ram:BillingSpecifiedPeriod") {
            date("ram:StartDateTime", p.start)
            date("ram:EndDateTime", p.end)
        }
    }

    /** BT-29/46: without a scheme as `ram:ID`, with a scheme (ISO 6523 ICD) as `ram:GlobalID`. */
    private fun XmlOut.identifiers(ids: List<Identifier>) {
        ids.filter { it.scheme == null }.forEach { text("ram:ID", it.value) }
        ids.filter { it.scheme != null }.forEach { text("ram:GlobalID", it.value, "schemeID" to it.scheme) }
    }

    private fun XmlOut.legalOrganization(
        id: Identifier?,
        tradingName: String?,
    ) {
        el("ram:SpecifiedLegalOrganization") {
            text("ram:ID", id?.value, "schemeID" to id?.scheme)
            text("ram:TradingBusinessName", tradingName)
        }
    }

    private fun XmlOut.contact(c: Contact?) {
        if (c == null) return
        el("ram:DefinedTradeContact") {
            text("ram:PersonName", c.point)
            el("ram:TelephoneUniversalCommunication") { text("ram:CompleteNumber", c.phone) }
            el("ram:EmailURIUniversalCommunication") { text("ram:URIID", c.email) }
        }
    }

    private fun XmlOut.address(a: Address?) {
        if (a == null) return
        el("ram:PostalTradeAddress") {
            text("ram:PostcodeCode", a.postCode)
            text("ram:LineOne", a.line1)
            text("ram:LineTwo", a.line2)
            text("ram:LineThree", a.line3)
            text("ram:CityName", a.city)
            text("ram:CountryID", a.countryCode)
            text("ram:CountrySubDivisionName", a.subdivision)
        }
    }

    private fun XmlOut.electronicAddress(id: Identifier?) {
        if (id == null) return
        el("ram:URIUniversalCommunication") { text("ram:URIID", id.value, "schemeID" to id.scheme) }
    }

    private fun XmlOut.taxRegistration(
        scheme: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) return
        el("ram:SpecifiedTaxRegistration") { text("ram:ID", value, "schemeID" to scheme) }
    }
}
