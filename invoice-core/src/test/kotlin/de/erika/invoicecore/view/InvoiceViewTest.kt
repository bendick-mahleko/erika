package de.erika.invoicecore.view

import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.model.Attachment
import de.erika.invoicecore.model.Bt
import de.erika.invoicecore.model.Identifier
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.support.Fixtures
import de.erika.invoicecore.support.Suites
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VW-01: every business term present in an official test file is shown with its German
 * label. VW-02: the PDF view copy contains the marking on every page and every value.
 */
class InvoiceViewTest {
    private val files: List<File> by lazy {
        (Suites.files(Suites.xrechnung, "xml") + Suites.files(Suites.enUbl, "xml") + Suites.files(Suites.enCii, "xml"))
            .filter { InvoiceParser.parse(it.readBytes()).invoice != null }
    }

    @Test
    fun `every EN 16931 business term and group has a German label`() {
        val expected = ((1..165).toSet() - 4).map { "BT-$it" } + (1..32).map { "BG-$it" }
        val missing = expected.filter { BtLabels.de(it).isNullOrBlank() }
        assertEquals(emptyList(), missing)
    }

    @TestFactory
    fun `every business term in the official test files is shown with its German label (VW-01)`(): List<DynamicTest> =
        files.map { file ->
            DynamicTest.dynamicTest(Suites.relative(file)) {
                val invoice = InvoiceParser.parse(file.readBytes()).invoice!!
                val view = InvoiceView.of(invoice)
                val fields = view.allFields()
                fields.forEach { f ->
                    assertEquals(BtLabels.de(f.bt), f.label, "label of ${f.bt}")
                    assertTrue(f.value.isNotBlank(), "${f.bt} has a value")
                }
                val shown = fields.map { it.bt }.toSet()
                val missing = presentTerms(invoice).filter { ids -> ids.none { it in shown } }
                assertEquals(emptyList(), missing, "business terms present in the file but not shown")
            }
        }

    @TestFactory
    fun `the PDF view copy carries the marking on every page and contains every value (VW-02)`(): List<DynamicTest> =
        Suites.files(Suites.xrechnung, "xml").filter { InvoiceParser.parse(it.readBytes()).invoice != null }.map { file ->
            DynamicTest.dynamicTest(Suites.relative(file)) {
                val view = InvoiceView.of(InvoiceParser.parse(file.readBytes()).invoice!!)
                val pdf = ViewRenderer.pdf(view)
                Loader.loadPDF(pdf).use { doc ->
                    val stripper = PDFTextStripper().apply { sortByPosition = true }
                    val pages =
                        (1..doc.numberOfPages).map { n ->
                            stripper.startPage = n
                            stripper.endPage = n
                            stripper.getText(doc)
                        }
                    pages.forEachIndexed { i, text ->
                        assertTrue(text.contains(ViewRenderer.DEFAULT_MARKING), "page ${i + 1} carries the marking")
                    }
                    // Values may continue on the next page: leave out the marking lines in between.
                    val all =
                        squash(
                            pages.joinToString(
                                "",
                            ) { page -> page.lines().filterNot { ViewRenderer.DEFAULT_MARKING in it }.joinToString("") },
                        )
                    val missing = view.allFields().filterNot { all.contains(squash(ViewRenderer.printable(it.value))) }
                    assertEquals(emptyList(), missing.map { "${it.bt}=${it.value}" }, "values missing in the PDF")
                }
            }
        }

    @Test
    fun `values are formatted for German readers`() {
        val view = InvoiceView.of(Fixtures.standard)
        val byBt = view.allFields().associate { it.bt to it.value }
        assertEquals("04.04.2016", byBt["BT-2"])
        assertEquals("380 – Rechnung", byBt["BT-3"])
        assertEquals("336,90 EUR", byBt["BT-112"])
        assertEquals("58 – SEPA-Überweisung", byBt["BT-81"])
        assertEquals("7 %", byBt["BT-119"])
        assertTrue(view.title.startsWith("Rechnung 123456XX"))
        val seller = view.sections.single { it.id == "BG-4" }
        assertEquals("BG-5", seller.sections.first().id)
        assertEquals(
            "Ort",
            seller.sections
                .first()
                .fields
                .single { it.bt == "BT-37" }
                .label,
        )
    }

    @Test
    fun `characters the PDF fonts can't show are replaced, umlauts and euro stay`() {
        assertEquals("Größe 5 € ? x", ViewRenderer.printable("Größe 5 € 中\tx"))
    }

    /** For each non-blank leaf value in the model: the BT IDs its property may carry. */
    private fun presentTerms(invoice: Invoice): List<List<String>> {
        val out = mutableListOf<List<String>>()

        fun walk(value: Any) {
            value::class.memberProperties.forEach { prop ->
                val v = prop.getter.call(value) ?: return@forEach
                val ids =
                    prop
                        .findAnnotation<Bt>()
                        ?.ids
                        ?.filter { it.startsWith("BT-") }
                        .orEmpty()
                val items = if (v is List<*>) v.filterNotNull() else listOf(v)
                items.forEach { item ->
                    when {
                        item is Identifier -> if (item.value.isNotBlank() && ids.isNotEmpty()) out += ids
                        item is Attachment -> if (ids.isNotEmpty()) out += ids
                        item::class.qualifiedName!!.startsWith("de.erika.invoicecore.model") -> walk(item)
                        item.toString().isNotBlank() && ids.isNotEmpty() -> out += ids
                    }
                }
            }
        }
        walk(invoice)
        return out.distinct()
    }

    private fun squash(text: String) = text.filterNot { it.isWhitespace() }
}
