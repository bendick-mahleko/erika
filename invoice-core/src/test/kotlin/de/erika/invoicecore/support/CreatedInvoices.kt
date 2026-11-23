package de.erika.invoicecore.support

import de.erika.invoicecore.model.Address
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
import de.erika.invoicecore.write.VatCase
import java.math.BigDecimal
import java.time.LocalDate

/** Invoices as the ERIKA creator builds them, one per VAT case (CR-04). */
object CreatedInvoices {
    fun invoice(
        case: VatCase = VatCase.STANDARD_19,
        typeCode: String = Invoice.TYPE_COMMERCIAL_INVOICE,
        secondLine: VatCase? = if (case == VatCase.STANDARD_19) VatCase.REDUCED_7 else null,
    ): Invoice {
        val foreign = case == VatCase.INTRA_EU_SUPPLY || case == VatCase.REVERSE_CHARGE
        val buyerAddress =
            if (foreign) {
                Address(line1 = "Rue de la Paix 3", postCode = "75002", city = "Paris", countryCode = "FR")
            } else {
                Address(line1 = "Kundenweg 2", postCode = "80331", city = "München", countryCode = "DE")
            }
        val lines =
            listOfNotNull(
                line("1", "Beratung vor Ort", "IT-Beratung & Einrichtung <Netzwerk>", "8", "HUR", "95.00", case),
                secondLine?.let { line("2", "Fachbuch", null, "3", "C62", "24.99", it) },
                line(if (secondLine == null) "2" else "3", "Anfahrt", null, "1", "LS", "49.90", case),
            )
        return Invoice(
            number = "RE-2026-0001",
            issueDate = LocalDate.of(2026, 11, 23),
            typeCode = typeCode,
            currency = "EUR",
            dueDate = LocalDate.of(2026, 12, 7),
            buyerReference = "K-1001",
            purchaseOrderReference = "PO-77",
            paymentTerms = "Zahlbar innerhalb von 14 Tagen ohne Abzug.",
            notes = listOfNotNull(case.note?.let { Note(text = it) }),
            precedingInvoices =
                if (typeCode == Invoice.TYPE_COMMERCIAL_INVOICE) {
                    emptyList()
                } else {
                    listOf(PrecedingInvoiceReference("RE-2026-0000", LocalDate.of(2026, 11, 2)))
                },
            seller =
                Seller(
                    name = "Mustermann Malerbetrieb GmbH",
                    vatId = if (case == VatCase.KLEINUNTERNEHMER) null else "DE123456789",
                    taxRegistrationId = "30/123/45678",
                    identifiers = if (case == VatCase.KLEINUNTERNEHMER) listOf(Identifier("30/123/45678")) else emptyList(),
                    electronicAddress = Identifier("rechnung@mustermann.example", "EM"),
                    address = Address(line1 = "Musterstraße 1", postCode = "10115", city = "Berlin", countryCode = "DE"),
                    contact = Contact("Erika Mustermann", "+49 30 1234567", "erika@mustermann.example"),
                ),
            buyer =
                Buyer(
                    name = if (foreign) "Client SARL" else "Kunde AG",
                    identifier = Identifier("K-1001"),
                    vatId = if (foreign) "FR12345678901" else null,
                    electronicAddress = Identifier("eingang@kunde.example", "EM"),
                    address = buyerAddress,
                ),
            delivery =
                Delivery(
                    actualDeliveryDate = LocalDate.of(2026, 11, 20),
                    deliverToAddress = if (case == VatCase.INTRA_EU_SUPPLY) buyerAddress else null,
                ),
            paymentInstructions =
                PaymentInstructions(
                    meansTypeCode = "58",
                    meansText = "SEPA-Überweisung",
                    remittanceInformation = "RE-2026-0001",
                    creditTransfers = listOf(CreditTransfer("DE02120300000000202051", "Mustermann Malerbetrieb GmbH", "BYLADEM1001")),
                ),
            lines = lines,
        )
    }

    fun line(
        id: String,
        name: String,
        description: String?,
        quantity: String,
        unit: String,
        price: String,
        case: VatCase,
        period: Period? = null,
    ) = InvoiceLine(
        id = id,
        quantity = BigDecimal(quantity),
        unitCode = unit,
        period = period,
        price = PriceDetails(netPrice = BigDecimal(price)),
        vatCategoryCode = case.categoryCode,
        vatRate = case.rate,
        item = Item(name = name, description = description, sellerId = "ART-$id"),
    )
}
