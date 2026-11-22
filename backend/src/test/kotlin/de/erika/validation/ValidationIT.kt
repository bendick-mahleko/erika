package de.erika.validation

import de.erika.support.WorkerIntegrationTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Stage 4 validation behaviour (VW-03, VW-04, VW-05, BR-07, NF-07). Conformance on all official files: ConformanceIT. */
class ValidationIT : WorkerIntegrationTest() {
    @Autowired lateinit var validator: InvoiceValidator

    @Autowired lateinit var packs: ValidationPacks

    private val fixtures = File(System.getProperty("erika.test-fixtures"))
    private val ubl = fixtures.resolve("xrechnung/instances/standard/01.01a-INVOICE_ubl.xml").readText()
    private val cii = fixtures.resolve("xrechnung/instances/standard/01.01a-INVOICE_uncefact.xml").readText()

    @Test
    fun `a valid XRechnung is valid, with pack, scenario and format recorded`() {
        val report = validator.validate(ubl.toByteArray())
        assertTrue(report.errors.isEmpty(), report.errors.toString())
        assertTrue(report.level != ValidationLevel.ERROR)
        assertTrue(report.applicable)
        assertEquals("xrechnung-3.0.2_2026-08-31", report.packId)
        assertEquals("EN16931 XRechnung (UBL Invoice)", report.scenario)
        assertEquals("XRECHNUNG_UBL", report.format)
        assertEquals(true, report.kositAccepted)

        val ciiReport = validator.validate(cii.toByteArray())
        assertEquals("EN16931 XRechnung (CII)", ciiReport.scenario)
        assertTrue(ciiReport.errors.isEmpty(), ciiReport.errors.toString())
    }

    @Test
    fun `a missing buyer reference is an error with a German explanation (BR-DE-15)`() {
        val broken = ubl.replace(Regex("<cbc:BuyerReference>[^<]*</cbc:BuyerReference>"), "")
        val report = validator.validate(broken.toByteArray())
        assertEquals(ValidationLevel.ERROR, report.level)
        val message = report.errors.single { it.ruleId == "BR-DE-15" }
        assertEquals(MessageSource.SCHEMATRON, message.source)
        assertTrue(message.text.startsWith("Die Käuferreferenz muss angegeben sein"), message.text)
        assertTrue(message.originalText!!.contains("Buyer reference"), "original rule text kept for experts")
        assertFalse(message.originalText!!.startsWith("[BR-DE-15]"), "rule prefix removed")
        assertEquals(false, report.kositAccepted)
    }

    @Test
    fun `a wrong total is flagged by the Schematron and by ERIKA's arithmetic check`() {
        val wrong = ubl.replace("<cbc:TaxInclusiveAmount currencyID=\"EUR\">336.9", "<cbc:TaxInclusiveAmount currencyID=\"EUR\">346.9")
        val report = validator.validate(wrong.toByteArray())
        assertEquals(ValidationLevel.ERROR, report.level)
        val ids = report.errors.map { it.ruleId }
        assertTrue("BR-CO-15" in ids, ids.toString())
        assertTrue(report.errors.any { it.source == MessageSource.CONTENT && it.ruleId.startsWith("ERIKA-ARITH") }, ids.toString())
        assertTrue(report.messages.all { it.text.isNotBlank() })
    }

    @Test
    fun `an XML Schema violation is reported as XSD with the parser's message as detail`() {
        val invalid = ubl.replace("<cbc:IssueDate>", "<cbc:Unexpected>x</cbc:Unexpected><cbc:IssueDate>")
        val report = validator.validate(invalid.toByteArray())
        val xsd = report.errors.first { it.ruleId == "XSD" }
        assertEquals(MessageSource.XML_SCHEMA, xsd.source)
        assertTrue(xsd.originalText!!.startsWith("cvc-"), xsd.originalText)
        assertTrue(xsd.text.startsWith("Die Datei entspricht nicht dem vorgeschriebenen XML-Schema"))
    }

    @Test
    fun `other EN 16931 specifications use ERIKA's EN 16931 scenario (e g Peppol BIS)`() {
        val peppol =
            ubl.replace(
                "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0",
                "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0",
            )
        val report = validator.validate(peppol.toByteArray())
        assertEquals("EN16931 CIUS/Extension (UBL Invoice)", report.scenario)
        assertTrue(report.messages.none { it.ruleId.startsWith("BR-DE") }, "no XRechnung rules for a non-XRechnung invoice")
    }

    @Test
    fun `an unknown specification identifier has no scenario and is an error`() {
        val unknown = ubl.replace("urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0", "urn:example:unknown")
        val report = validator.validate(unknown.toByteArray())
        assertFalse(report.applicable, "invoice-core: unknown profile, not an e-invoice")
        assertEquals("ERIKA-NOT-EINVOICE-UNKNOWN_PROFILE", report.errors.single().ruleId)
    }

    @Test
    fun `files that are not e-invoices are labelled, not validated`() {
        val plainPdf = pdf(attachment = null)
        val pdfReport = validator.validate(plainPdf)
        assertFalse(pdfReport.applicable)
        assertEquals("PDF_ONLY", pdfReport.format)
        assertEquals("ERIKA-NOT-EINVOICE-PLAIN_PDF", pdfReport.messages.single().ruleId)
        assertTrue(
            pdfReport.messages
                .single()
                .text
                .contains("sonstige Rechnung"),
        )

        val image = validator.validate(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
        assertFalse(image.applicable)
        assertEquals("ERIKA-FORMAT-UNKNOWN_FILE_TYPE", image.messages.single().ruleId)

        val truncated = validator.validate(ubl.take(500).toByteArray())
        assertTrue(truncated.applicable, "a damaged invoice is a failed e-invoice, not 'something else'")
        assertEquals("ERIKA-FORMAT-MALFORMED_XML", truncated.errors.single().ruleId)
    }

    @Test
    fun `an official ZUGFeRD sample passes XML rules and PDF A-3`() {
        val sample = fixtures.walkTopDown().first { it.name == "EN16931_Einfach.pdf" && it.path.contains("XML-Rechnung") }
        val report = validator.validate(sample.readBytes())
        assertEquals("ZUGFERD", report.format)
        assertEquals("EN16931", report.profile)
        assertNotNull(report.scenario)
        assertTrue(
            report.messages.none { it.source == MessageSource.PDFA },
            report.messages.filter { it.source == MessageSource.PDFA }.toString(),
        )
        assertTrue(report.errors.isEmpty(), report.errors.toString())
    }

    @Test
    fun `a ZUGFeRD hybrid that is not PDF A-3 fails the PDF A check`() {
        val report = validator.validate(pdf(attachment = cii.toByteArray()))
        assertEquals("ZUGFERD", report.format)
        val pdfa = report.errors.filter { it.source == MessageSource.PDFA }
        assertTrue(pdfa.isNotEmpty(), report.messages.toString())
        assertTrue(pdfa.all { it.ruleId == "PDFA-3" && it.text.contains("PDF/A-3") && it.location != null })
    }

    @Test
    fun `validation of a typical invoice takes less than 5 seconds (NF-07)`() {
        validator.validate(ubl.toByteArray()) // warm caches of this test JVM
        val times = (1..5).map { validator.validate(ubl.toByteArray()).durationMillis }
        println("NF-07 validation durations (XRechnung UBL): $times ms")
        assertTrue(times.max() < 5_000, "durations: $times ms")
    }

    @Test
    fun `the pack in force is chosen by date (BR-07)`() {
        val pack = packs.byId("xrechnung-3.0.2_2026-08-31")
        assertEquals(pack, packs.activeOn(LocalDate.of(2026, 11, 22)))
        assertFailsWith<IllegalStateException> { packs.activeOn(LocalDate.of(2026, 9, 1)) }
        assertEquals(pack.id, validator.validate(ubl.toByteArray(), LocalDate.of(2027, 1, 1)).packId)
    }

    /** A PDF (not PDF/A) with an optional embedded `factur-x.xml`. */
    private fun pdf(attachment: ByteArray?): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            if (attachment != null) {
                val spec =
                    PDComplexFileSpecification().apply {
                        file = "factur-x.xml"
                        embeddedFile = PDEmbeddedFile(doc, attachment.inputStream()).apply { subtype = "text/xml" }
                    }
                val tree = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("factur-x.xml" to spec) }
                doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = tree }
            }
            ByteArrayOutputStream().also(doc::save).toByteArray()
        }
}
