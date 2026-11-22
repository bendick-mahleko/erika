package de.erika.invoicecore.support

import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.model.Invoice
import java.io.File

/** Valid invoices from the official test suite, used as starting points for unit tests. */
object Fixtures {
    fun xrechnung(name: String): File = Suites.files(Suites.xrechnung, "xml").single { it.name == name }

    fun invoice(name: String): Invoice = InvoiceParser.parse(xrechnung(name).readBytes()).invoice!!

    /** Standard invoice: 2 lines, one VAT category (S 19 %). */
    val standard: Invoice get() = invoice("01.01a-INVOICE_ubl.xml")

    /** Reverse charge (AE). */
    val reverseCharge: Invoice get() = invoice("01.21a-INVOICE_ubl.xml")
}
