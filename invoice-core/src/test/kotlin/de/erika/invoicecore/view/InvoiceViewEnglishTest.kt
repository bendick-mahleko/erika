package de.erika.invoicecore.view

import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.support.CreatedInvoices
import de.erika.invoicecore.support.Suites
import de.erika.invoicecore.write.InvoiceWriter
import de.erika.invoicecore.write.OutputFormat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VW-07: the invoice view in English, with every label available in both languages. */
class InvoiceViewEnglishTest {
    @Test
    fun `every ID with a German label has an English one`() {
        val missing = BtLabels.ids.filter { BtLabels.en(it).isNullOrBlank() }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `the English view uses English labels, ISO dates, English numbers and code meanings`() {
        // Prepared as the writer does, so the totals are filled in.
        val invoice = InvoiceWriter.prepare(CreatedInvoices.invoice(), OutputFormat.XRECHNUNG_CII)
        val view = InvoiceView.of(invoice, ViewLanguage.EN)
        view.allFields().forEach { assertEquals(BtLabels.en(it.bt), it.label, it.bt) }
        val byBt = view.allFields().associateBy { it.bt }
        assertTrue(byBt.getValue("BT-2").value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")), byBt.getValue("BT-2").value)
        assertTrue(byBt.getValue("BT-3").value.startsWith("380 – Commercial invoice"))
        val total = byBt.getValue("BT-112").value
        assertTrue(total.matches(Regex("[\\d,]+\\.\\d{2} EUR")), total)
        assertTrue(view.title.startsWith("Commercial invoice"))
        assertEquals("Invoice", view.sections.first().title)
        val german = InvoiceView.of(invoice)
        assertEquals("Rechnung", german.sections.first().title, "German stays the default")
        assertEquals(german.allFields().map { it.bt }, view.allFields().map { it.bt }, "same fields in both languages")
    }

    @Test
    fun `official test files show the same business terms in English`() {
        Suites.files(Suites.xrechnung, "xml").take(10).forEach { file ->
            val invoice = InvoiceParser.parse(file.readBytes()).invoice ?: return@forEach
            val en = InvoiceView.of(invoice, ViewLanguage.EN).allFields()
            assertEquals(InvoiceView.of(invoice).allFields().map { it.bt }, en.map { it.bt }, Suites.relative(file))
            en.forEach { assertEquals(BtLabels.en(it.bt), it.label) }
        }
    }
}
