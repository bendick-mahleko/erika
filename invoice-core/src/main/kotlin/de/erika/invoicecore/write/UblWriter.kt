package de.erika.invoicecore.write

import de.erika.invoicecore.model.Address
import de.erika.invoicecore.model.AllowanceCharge
import de.erika.invoicecore.model.Identifier
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.InvoiceLine
import de.erika.invoicecore.write.XmlOut.Companion.ISO_DATE
import de.erika.invoicecore.xml.Ns

/**
 * Writes the neutral model as a UBL 2.1 `Invoice`, or a `CreditNote` for type code 381,
 * following the EN 16931-3-2 binding and the element order of the UBL schemas. The mapping
 * is the inverse of [de.erika.invoicecore.ubl.UblMapper].
 */
internal object UblWriter {
    fun write(invoice: Invoice): ByteArray {
        val creditNote = invoice.typeCode == Invoice.TYPE_CREDIT_NOTE
        val rootNs = if (creditNote) Ns.UBL_CREDIT_NOTE else Ns.UBL_INVOICE
        val x = XmlOut(if (creditNote) "CreditNote" else "Invoice", listOf("" to rootNs, "cac" to Ns.CAC, "cbc" to Ns.CBC))
        val cur = "currencyID" to invoice.currency

        x.text("cbc:CustomizationID", invoice.specificationIdentifier)
        x.text("cbc:ProfileID", invoice.businessProcessType)
        x.text("cbc:ID", invoice.number)
        x.text("cbc:IssueDate", invoice.issueDate?.format(ISO_DATE))
        if (!creditNote) x.text("cbc:DueDate", invoice.dueDate?.format(ISO_DATE))
        x.text(if (creditNote) "cbc:CreditNoteTypeCode" else "cbc:InvoiceTypeCode", invoice.typeCode)
        // UBL has no element for BT-21: the binding writes it as a "#CODE#" prefix.
        invoice.notes.forEach { n -> x.text("cbc:Note", if (n.subjectCode != null) "#${n.subjectCode}#${n.text}" else n.text) }
        x.text("cbc:DocumentCurrencyCode", invoice.currency)
        x.text("cbc:AccountingCost", invoice.buyerAccountingReference)
        x.text("cbc:BuyerReference", invoice.buyerReference)
        invoice.delivery?.invoicingPeriod?.let { p ->
            x.el("cac:InvoicePeriod") {
                text("cbc:StartDate", p.start?.format(ISO_DATE))
                text("cbc:EndDate", p.end?.format(ISO_DATE))
            }
        }
        if (invoice.purchaseOrderReference != null || invoice.salesOrderReference != null) {
            x.el("cac:OrderReference") {
                text("cbc:ID", invoice.purchaseOrderReference ?: "NA")
                text("cbc:SalesOrderID", invoice.salesOrderReference)
            }
        }
        invoice.precedingInvoices.forEach { ref ->
            x.el("cac:BillingReference") {
                el("cac:InvoiceDocumentReference") {
                    text("cbc:ID", ref.number)
                    text("cbc:IssueDate", ref.issueDate?.format(ISO_DATE))
                }
            }
        }
        x.el("cac:ContractDocumentReference") { text("cbc:ID", invoice.contractReference) }
        // BG-24: supporting documents, e.g. a signed job report (EN 16931-3-2).
        invoice.supportingDocuments.forEach { d ->
            x.el("cac:AdditionalDocumentReference") {
                text("cbc:ID", d.reference)
                text("cbc:DocumentDescription", d.description)
                if (d.attachment != null || d.externalLocation != null) {
                    el("cac:Attachment") {
                        d.attachment?.let {
                            text(
                                "cbc:EmbeddedDocumentBinaryObject",
                                java.util.Base64
                                    .getEncoder()
                                    .encodeToString(it.content),
                                "mimeCode" to it.mimeCode,
                                "filename" to it.filename,
                            )
                        }
                        el("cac:ExternalReference") { text("cbc:URI", d.externalLocation) }
                    }
                }
            }
        }

        x.el("cac:AccountingSupplierParty") {
            el("cac:Party") {
                val s = invoice.seller
                endpoint(s.electronicAddress)
                s.identifiers.forEach { id -> el("cac:PartyIdentification") { text("cbc:ID", id.value, "schemeID" to id.scheme) } }
                el("cac:PartyName") { text("cbc:Name", s.tradingName) }
                address("cac:PostalAddress", s.address)
                taxScheme(s.vatId, "VAT")
                taxScheme(s.taxRegistrationId, "FC")
                el("cac:PartyLegalEntity") {
                    text("cbc:RegistrationName", s.name)
                    text("cbc:CompanyID", s.legalRegistrationId?.value, "schemeID" to s.legalRegistrationId?.scheme)
                    text("cbc:CompanyLegalForm", s.additionalLegalInformation)
                }
                s.contact?.let { c ->
                    el("cac:Contact") {
                        text("cbc:Name", c.point)
                        text("cbc:Telephone", c.phone)
                        text("cbc:ElectronicMail", c.email)
                    }
                }
            }
        }
        x.el("cac:AccountingCustomerParty") {
            el("cac:Party") {
                val b = invoice.buyer
                endpoint(b.electronicAddress)
                b.identifier?.let { id -> el("cac:PartyIdentification") { text("cbc:ID", id.value, "schemeID" to id.scheme) } }
                el("cac:PartyName") { text("cbc:Name", b.tradingName) }
                address("cac:PostalAddress", b.address)
                taxScheme(b.vatId, "VAT")
                el("cac:PartyLegalEntity") {
                    text("cbc:RegistrationName", b.name)
                    text("cbc:CompanyID", b.legalRegistrationId?.value, "schemeID" to b.legalRegistrationId?.scheme)
                }
                b.contact?.let { c ->
                    el("cac:Contact") {
                        text("cbc:Name", c.point)
                        text("cbc:Telephone", c.phone)
                        text("cbc:ElectronicMail", c.email)
                    }
                }
            }
        }
        invoice.delivery?.let { d ->
            x.el("cac:Delivery") {
                text("cbc:ActualDeliveryDate", d.actualDeliveryDate?.format(ISO_DATE))
                el("cac:DeliveryLocation") {
                    text("cbc:ID", d.locationIdentifier?.value, "schemeID" to d.locationIdentifier?.scheme)
                    address("cac:Address", d.deliverToAddress)
                }
                el("cac:DeliveryParty") { el("cac:PartyName") { text("cbc:Name", d.deliverToName) } }
            }
        }
        invoice.paymentInstructions?.let { p ->
            val transfers = p.creditTransfers.ifEmpty { listOf(null) }
            transfers.forEach { ct ->
                x.el("cac:PaymentMeans") {
                    text("cbc:PaymentMeansCode", p.meansTypeCode, "name" to p.meansText)
                    if (creditNote) text("cbc:PaymentDueDate", invoice.dueDate?.format(ISO_DATE))
                    text("cbc:PaymentID", p.remittanceInformation)
                    if (ct != null) {
                        el("cac:PayeeFinancialAccount") {
                            text("cbc:ID", ct.accountId)
                            text("cbc:Name", ct.accountName)
                            el("cac:FinancialInstitutionBranch") { text("cbc:ID", ct.serviceProviderId) }
                        }
                    }
                }
            }
        }
        x.el("cac:PaymentTerms") { text("cbc:Note", invoice.paymentTerms) }
        invoice.charges.forEach { x.allowanceCharge(it, charge = true, currency = invoice.currency, withVat = true) }
        invoice.allowances.forEach { x.allowanceCharge(it, charge = false, currency = invoice.currency, withVat = true) }
        x.el("cac:TaxTotal") {
            amount("cbc:TaxAmount", invoice.totals.totalVat, cur)
            invoice.vatBreakdown.forEach { v ->
                el("cac:TaxSubtotal") {
                    amount("cbc:TaxableAmount", v.taxableAmount, cur)
                    amount("cbc:TaxAmount", v.taxAmount, cur)
                    el("cac:TaxCategory") {
                        text("cbc:ID", v.categoryCode)
                        amount("cbc:Percent", v.rate)
                        text("cbc:TaxExemptionReasonCode", v.exemptionReasonCode)
                        text("cbc:TaxExemptionReason", v.exemptionReason)
                        el("cac:TaxScheme") { text("cbc:ID", "VAT") }
                    }
                }
            }
        }
        val t = invoice.totals
        x.el("cac:LegalMonetaryTotal") {
            amount("cbc:LineExtensionAmount", t.lineNetTotal, cur)
            amount("cbc:TaxExclusiveAmount", t.totalWithoutVat, cur)
            amount("cbc:TaxInclusiveAmount", t.totalWithVat, cur)
            amount("cbc:AllowanceTotalAmount", t.allowanceTotal, cur)
            amount("cbc:ChargeTotalAmount", t.chargeTotal, cur)
            amount("cbc:PrepaidAmount", t.paidAmount, cur)
            amount("cbc:PayableRoundingAmount", t.roundingAmount, cur)
            amount("cbc:PayableAmount", t.amountDue, cur)
        }
        invoice.lines.forEach { x.line(it, creditNote, invoice.currency) }
        return x.finish()
    }

    private fun XmlOut.line(
        line: InvoiceLine,
        creditNote: Boolean,
        currency: String?,
    ) {
        val cur = "currencyID" to currency
        el(if (creditNote) "cac:CreditNoteLine" else "cac:InvoiceLine") {
            text("cbc:ID", line.id)
            text("cbc:Note", line.note)
            amount(if (creditNote) "cbc:CreditedQuantity" else "cbc:InvoicedQuantity", line.quantity, "unitCode" to line.unitCode)
            amount("cbc:LineExtensionAmount", line.netAmount, cur)
            text("cbc:AccountingCost", line.buyerAccountingReference)
            line.period?.let { p ->
                el("cac:InvoicePeriod") {
                    text("cbc:StartDate", p.start?.format(ISO_DATE))
                    text("cbc:EndDate", p.end?.format(ISO_DATE))
                }
            }
            el("cac:OrderLineReference") { text("cbc:LineID", line.orderLineReference) }
            line.charges.forEach { allowanceCharge(it, charge = true, currency = currency, withVat = false) }
            line.allowances.forEach { allowanceCharge(it, charge = false, currency = currency, withVat = false) }
            el("cac:Item") {
                text("cbc:Description", line.item.description)
                text("cbc:Name", line.item.name)
                el("cac:BuyersItemIdentification") { text("cbc:ID", line.item.buyerId) }
                el("cac:SellersItemIdentification") { text("cbc:ID", line.item.sellerId) }
                el("cac:ClassifiedTaxCategory") {
                    text("cbc:ID", line.vatCategoryCode)
                    amount("cbc:Percent", line.vatRate)
                    el("cac:TaxScheme") { text("cbc:ID", "VAT") }
                }
            }
            el("cac:Price") {
                amount("cbc:PriceAmount", line.price.netPrice, cur)
                amount("cbc:BaseQuantity", line.price.baseQuantity, "unitCode" to line.price.baseQuantityUnitCode)
            }
        }
    }

    private fun XmlOut.allowanceCharge(
        ac: AllowanceCharge,
        charge: Boolean,
        currency: String?,
        withVat: Boolean,
    ) {
        val cur = "currencyID" to currency
        el("cac:AllowanceCharge") {
            text("cbc:ChargeIndicator", charge.toString())
            text("cbc:AllowanceChargeReasonCode", ac.reasonCode)
            text("cbc:AllowanceChargeReason", ac.reason)
            amount("cbc:MultiplierFactorNumeric", ac.percentage)
            amount("cbc:Amount", ac.amount, cur)
            amount("cbc:BaseAmount", ac.baseAmount, cur)
            if (withVat) {
                el("cac:TaxCategory") {
                    text("cbc:ID", ac.vatCategoryCode)
                    amount("cbc:Percent", ac.vatRate)
                    el("cac:TaxScheme") { text("cbc:ID", "VAT") }
                }
            }
        }
    }

    private fun XmlOut.endpoint(id: Identifier?) {
        if (id == null) return
        text("cbc:EndpointID", id.value, "schemeID" to id.scheme)
    }

    private fun XmlOut.taxScheme(
        value: String?,
        scheme: String,
    ) {
        if (value.isNullOrBlank()) return
        el("cac:PartyTaxScheme") {
            text("cbc:CompanyID", value)
            el("cac:TaxScheme") { text("cbc:ID", scheme) }
        }
    }

    private fun XmlOut.address(
        name: String,
        a: Address?,
    ) {
        if (a == null) return
        el(name) {
            text("cbc:StreetName", a.line1)
            text("cbc:AdditionalStreetName", a.line2)
            text("cbc:CityName", a.city)
            text("cbc:PostalZone", a.postCode)
            text("cbc:CountrySubentity", a.subdivision)
            el("cac:AddressLine") { text("cbc:Line", a.line3) }
            el("cac:Country") { text("cbc:IdentificationCode", a.countryCode) }
        }
    }
}
