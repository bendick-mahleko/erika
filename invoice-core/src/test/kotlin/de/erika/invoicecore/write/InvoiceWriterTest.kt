package de.erika.invoicecore.write

import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.check.InvoiceChecks
import de.erika.invoicecore.check.Severity
import de.erika.invoicecore.model.Attachment
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.InvoiceFormat
import de.erika.invoicecore.model.Profile
import de.erika.invoicecore.model.SupportingDocument
import de.erika.invoicecore.model.Syntax
import de.erika.invoicecore.support.CreatedInvoices
import de.erika.invoicecore.support.ModelDump
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round trip (Build Plan Stage 8): generate → parse with our own reader → same model, for
 * every format, VAT case and document type. The official validation (KoSIT, veraPDF) of the
 * same files runs in the ERIKA back end (`CreatorConformanceIT`).
 */
class InvoiceWriterTest {
    private val types =
        listOf(Invoice.TYPE_COMMERCIAL_INVOICE, Invoice.TYPE_CREDIT_NOTE, Invoice.TYPE_CORRECTED_INVOICE)

    @TestFactory
    fun `every format, VAT case and type reads back as the same invoice`(): List<DynamicTest> =
        OutputFormat.entries.flatMap { format ->
            VatCase.entries.flatMap { case ->
                types.map { type ->
                    dynamicTest("$format / $case / $type") {
                        val source = CreatedInvoices.invoice(case, type)
                        val expected = InvoiceWriter.prepare(source, format)
                        val bytes = InvoiceWriter.write(source, format)
                        val parsed = InvoiceParser.parse(bytes)

                        val f = parsed.format
                        assertEquals(expectedFormat(format), f.format)
                        assertEquals(if (format == OutputFormat.ZUGFERD_EN16931) Profile.EN16931 else Profile.XRECHNUNG, f.profile)
                        assertTrue(f.isEInvoice)
                        assertEquals(if (format == OutputFormat.XRECHNUNG_UBL) Syntax.UBL else Syntax.CII, f.syntax)
                        assertEquals(ModelDump.render(expected), ModelDump.render(parsed.invoice))
                        val errors = InvoiceChecks.check(parsed.invoice!!).filter { it.severity == Severity.ERROR }
                        assertTrue(errors.isEmpty(), "content errors: $errors")
                    }
                }
            }
        }

    @TestFactory
    fun `a supporting document with an embedded PDF survives every format and VAT case (BG-24)`(): List<DynamicTest> =
        OutputFormat.entries.flatMap { format ->
            VatCase.entries.map { case ->
                dynamicTest("$format / $case") {
                    val pdf = "%PDF-1.7 Arbeitsbericht".toByteArray() + ByteArray(2048) { (it % 251).toByte() }
                    val document =
                        SupportingDocument(
                            reference = "Bericht-ABCDEFGHIJ",
                            description = "Unterschriebener Arbeitsbericht vom 10.05.2027",
                            attachment = Attachment("application/pdf", "Arbeitsbericht-ABCDEFGHIJ.pdf", pdf),
                        )
                    val source = CreatedInvoices.invoice(case).copy(supportingDocuments = listOf(document))
                    val parsed = InvoiceParser.parse(InvoiceWriter.write(source, format)).invoice!!
                    assertEquals(listOf(document), parsed.supportingDocuments)
                    assertContentEquals(
                        pdf,
                        parsed.supportingDocuments
                            .single()
                            .attachment!!
                            .content,
                    )
                    assertEquals(ModelDump.render(InvoiceWriter.prepare(source, format)), ModelDump.render(parsed))
                }
            }
        }

    @Test
    fun `each VAT case carries its category code, rate and note`() {
        VatCase.entries.forEach { case ->
            val invoice = InvoiceWriter.prepare(CreatedInvoices.invoice(case, secondLine = null), OutputFormat.XRECHNUNG_CII)
            val vat = invoice.vatBreakdown.single()
            assertEquals(case.categoryCode, vat.categoryCode, "$case")
            assertEquals(0, case.rate.compareTo(vat.rate), "$case")
            assertEquals(case.exemptionReason, vat.exemptionReason, "$case")
            assertEquals(case.exemptionReasonCode, vat.exemptionReasonCode, "$case")
            assertEquals(listOfNotNull(case.note), invoice.notes.map { it.text }, "$case")
        }
        assertContains(VatCase.KLEINUNTERNEHMER.note!!, "§ 19 UStG")
        assertContains(VatCase.REVERSE_CHARGE.note!!, "§ 13b UStG")
    }

    @Test
    fun `UBL credit notes use the CreditNote document`() {
        val xml =
            InvoiceWriter
                .write(
                    CreatedInvoices.invoice(typeCode = Invoice.TYPE_CREDIT_NOTE),
                    OutputFormat.XRECHNUNG_UBL,
                ).decodeToString()
        assertContains(xml, "<CreditNote xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2\"")
        assertContains(xml, "<cbc:CreditNoteTypeCode>381</cbc:CreditNoteTypeCode>")
        assertContains(xml, "<cbc:CreditedQuantity unitCode=\"HUR\">8</cbc:CreditedQuantity>")
        assertContains(xml, "<cbc:PaymentDueDate>2026-12-07</cbc:PaymentDueDate>")
        assertContains(xml, "<cbc:ID>RE-2026-0000</cbc:ID>")
    }

    @Test
    fun `the same invoice always gives the same bytes`() {
        OutputFormat.entries.forEach { format ->
            val a = InvoiceWriter.write(CreatedInvoices.invoice(), format)
            val b = InvoiceWriter.write(CreatedInvoices.invoice(), format)
            assertContentEquals(a, b, "$format is not deterministic")
        }
    }

    @Test
    fun `text is escaped in XML and replaced in the PDF when the font lacks a glyph`() {
        val source =
            CreatedInvoices.invoice().let {
                it.copy(lines = it.lines.map { l -> l.copy(item = l.item.copy(name = "A & B <c> \"d\" \u0007 😀")) })
            }
        val xml = InvoiceWriter.write(source, OutputFormat.XRECHNUNG_CII).decodeToString()
        assertContains(xml, "A &amp; B &lt;c&gt; &quot;d&quot;")
        val parsed = InvoiceParser.parse(xml.toByteArray()).invoice!!
        assertTrue(parsed.lines.all { it.item.name!!.startsWith("A & B <c> \"d\"") })

        val pdf = InvoiceWriter.write(source, OutputFormat.ZUGFERD_EN16931)
        val text =
            Loader.loadPDF(pdf).use {
                org.apache.pdfbox.text
                    .PDFTextStripper()
                    .getText(it)
            }
        assertContains(text, "A & B <c> \"d\"")
        assertContains(text, "Rechnung RE-2026-0001")
        assertContains(text, "factur-x.xml")
    }

    @Test
    fun `the ZUGFeRD PDF is a PDF-A-3 with the XML attached as Alternative`() {
        val pdf = InvoiceWriter.write(CreatedInvoices.invoice(VatCase.KLEINUNTERNEHMER), OutputFormat.ZUGFERD_EN16931)
        Loader.loadPDF(pdf).use { doc ->
            val catalog = doc.documentCatalog
            val spec =
                catalog.names.embeddedFiles.names
                    .getValue("factur-x.xml")
            assertEquals("Alternative", spec.cosObject.getNameAsString("AFRelationship"))
            assertEquals("text/xml", spec.embeddedFile.subtype)
            assertTrue(catalog.cosObject.containsKey("AF"))
            assertEquals(1, catalog.outputIntents.size)
            val xmp = catalog.metadata.toByteArray().decodeToString()
            assertContains(xmp, "<pdfaid:part>3</pdfaid:part>")
            assertContains(xmp, "<fx:ConformanceLevel>EN 16931</fx:ConformanceLevel>")
            val text =
                org.apache.pdfbox.text
                    .PDFTextStripper()
                    .getText(doc)
            assertContains(text, "§ 19 UStG")
            assertContains(text, "Steuernummer 30/123/45678")
        }
    }

    @Test
    fun `long invoices continue on further pages`() {
        val base = CreatedInvoices.invoice()
        val many =
            (1..80).map {
                CreatedInvoices.line(
                    "$it",
                    "Position $it mit einer etwas längeren Bezeichnung",
                    "Beschreibung",
                    "1",
                    "C62",
                    "1.00",
                    VatCase.STANDARD_19,
                )
            }
        val pdf = InvoiceWriter.write(base.copy(lines = many), OutputFormat.ZUGFERD_EN16931)
        Loader.loadPDF(pdf).use { assertTrue(it.numberOfPages >= 3, "pages: ${it.numberOfPages}") }
        val parsed = InvoiceParser.parse(pdf).invoice!!
        assertEquals(0, BigDecimal("95.20").compareTo(parsed.totals.totalWithVat))
    }

    @Test
    fun `file names keep only safe characters`() {
        val invoice = CreatedInvoices.invoice().copy(number = "RE/2026 0001")
        assertEquals("RE_2026_0001.pdf", InvoiceWriter.fileName(invoice, OutputFormat.ZUGFERD_EN16931))
        assertEquals("rechnung.xml", InvoiceWriter.fileName(invoice.copy(number = null), OutputFormat.XRECHNUNG_UBL))
    }

    private fun expectedFormat(format: OutputFormat) =
        when (format) {
            OutputFormat.XRECHNUNG_CII -> InvoiceFormat.XRECHNUNG_CII
            OutputFormat.XRECHNUNG_UBL -> InvoiceFormat.XRECHNUNG_UBL
            OutputFormat.ZUGFERD_EN16931 -> InvoiceFormat.ZUGFERD
        }
}
