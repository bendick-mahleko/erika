package de.erika.invoicecore.spike

import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.check.InvoiceChecks
import de.erika.invoicecore.check.Severity
import de.erika.invoicecore.model.InvoiceFormat
import de.erika.invoicecore.model.Profile
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.XmpSerializer
import org.junit.jupiter.api.Test
import org.mustangproject.BankDetails
import org.mustangproject.Contact
import org.mustangproject.Item
import org.mustangproject.Product
import org.mustangproject.TradeParty
import org.mustangproject.ZUGFeRD.Profiles
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromPDFA
import org.mustangproject.validator.ZUGFeRDValidator
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mustangproject.Invoice as MustangInvoice

/**
 * Spike S2-a (Software Design open point 2): can Mustangproject produce stable
 * ZUGFeRD/Factur-X PDF/A-3 files that pass veraPDF and the XML rules, for the
 * cases ERIKA must generate (CR-02, CR-04)?
 *
 * Steps per case: build a PDF/A-3b visual part with PDFBox → Mustang embeds the
 * XML → Mustang's validator checks PDF/A (veraPDF) and XML (XSD + Schematron) →
 * our own parser reads the result back. Run with `./gradlew spikeTest`.
 * Output files go to build/spike for manual inspection.
 */
class ZugferdGenerationSpike {
    private val out = File(System.getProperty("erika.spikeOut") ?: "build/spike").also { it.mkdirs() }

    @Test
    fun `standard invoice 19 percent, profile EN16931`() = run("standard-en16931", "EN16931", vat = "19")

    @Test
    fun `reduced rate 7 percent, profile EN16931`() = run("reduced-en16931", "EN16931", vat = "7")

    @Test
    fun `standard invoice, profile XRECHNUNG in PDF`() = run("standard-xrechnung", "XRECHNUNG", vat = "19")

    private fun run(
        name: String,
        profile: String,
        vat: String,
    ) {
        val pdf = File(out, "$name.pdf")
        export(pdf, profile, vat)

        // 1. veraPDF (PDF/A-3) + XML schema and Schematron, as Mustang's validator runs them.
        //    The report has one <summary> per part (PDF, XML) plus an overall one: all must be valid.
        val validator = ZUGFeRDValidator()
        val report = validator.validate(pdf.absolutePath)
        File(out, "$name-validation.xml").writeText(report)
        val summaries = Regex("""<summary status="([a-z]+)"""").findAll(report).map { it.groupValues[1] }.toList()
        println("[$name] validator summaries: $summaries, completely valid: ${validator.wasCompletelyValid()}")
        assertTrue(summaries.isNotEmpty() && summaries.all { it == "valid" }, "validator report: build/spike/$name-validation.xml")

        // 2. Our parser reads the generated file back.
        val parsed = InvoiceParser.parse(pdf.readBytes())
        assertEquals(InvoiceFormat.ZUGFERD, parsed.format.format)
        assertEquals(if (profile == "XRECHNUNG") Profile.XRECHNUNG else Profile.EN16931, parsed.format.profile)
        val errors = InvoiceChecks.check(parsed.invoice!!).filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), "content errors: $errors")
        val total = parsed.invoice!!.totals.totalWithVat
        println("[$name] parsed back: ${parsed.format.format}/${parsed.format.profile}, total ${total?.toPlainString()}")

        // 3. Stability: a second export of the same data yields the same invoice XML.
        val again = File(out, "$name-2.pdf")
        export(again, profile, vat)
        assertEquals(embeddedXml(pdf), embeddedXml(again), "two exports of the same invoice differ")
    }

    private fun export(
        target: File,
        profile: String,
        vat: String,
    ) {
        ZUGFeRDExporterFromPDFA().use { exporter ->
            exporter
                .load(visualPdfA3())
                .setProducer("ERIKA spike S2-a")
                .setCreator("ERIKA")
                .setProfile(Profiles.getByName(profile))
            exporter.setTransaction(invoice(vat))
            exporter.export(target.absolutePath)
        }
    }

    private fun embeddedXml(pdf: File): String =
        org.apache.pdfbox.Loader.loadPDF(pdf).use { doc ->
            doc.documentCatalog.names.embeddedFiles.names.values
                .single()
                .embeddedFile
                .toByteArray()
                .decodeToString()
        }

    private fun invoice(vat: String): MustangInvoice {
        val today = Date.from(LocalDate.of(2026, 11, 22).atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant())
        return MustangInvoice()
            .setNumber("RE-2026-0001")
            .setIssueDate(today)
            .setDueDate(today)
            .setDeliveryDate(today)
            .setReferenceNumber("04011000-12345-34")
            .setSender(
                TradeParty("Musterfirma GmbH", "Musterstraße 1", "10115", "Berlin", "DE")
                    .addVATID("DE123456789")
                    .addTaxID("30/123/45678")
                    .setEmail("rechnung@musterfirma.example")
                    // XRechnung requires the seller contact (BG-6, BR-DE-2).
                    .setContact(Contact("Erika Mustermann", "+49 30 1234567", "erika@musterfirma.example"))
                    .addBankDetails(BankDetails("DE02120300000000202051", "BYLADEM1001")),
            ).setRecipient(
                TradeParty("Kunde AG", "Kundenweg 2", "80331", "München", "DE")
                    .setEmail("eingang@kunde.example"),
            ).addItem(Item(Product("Beratung", "IT-Beratung vor Ort", "HUR", BigDecimal(vat)), BigDecimal("95.00"), BigDecimal("8")))
            .addItem(Item(Product("Reisekosten", "Pauschale", "C62", BigDecimal(vat)), BigDecimal("49.90"), BigDecimal("1")))
    }

    /** Minimal PDF/A-3b page: embedded font, sRGB output intent, XMP with pdfaid. */
    private fun visualPdfA3(): ByteArray =
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            val font =
                PDDocument::class.java.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")!!.use {
                    PDType0Font.load(doc, it)
                }
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(font, 14f)
                cs.newLineAtOffset(60f, 760f)
                cs.showText("Rechnung RE-2026-0001 (Ansichtskopie, Original ist die XML-Datei)")
                cs.endText()
            }
            val xmp = XMPMetadata.createXMPMetadata()
            xmp.createAndAddPDFAIdentificationSchema().apply {
                part = 3
                conformance = "B"
            }
            xmp.createAndAddDublinCoreSchema().apply { title = "Rechnung RE-2026-0001" }
            val xmpBytes = ByteArrayOutputStream().also { XmpSerializer().serialize(xmp, it, true) }.toByteArray()
            doc.documentCatalog.metadata = PDMetadata(doc).apply { importXMPMetadata(xmpBytes) }
            val icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data
            doc.documentCatalog.addOutputIntent(
                PDOutputIntent(doc, icc.inputStream()).apply {
                    info = "sRGB IEC61966-2.1"
                    outputCondition = "sRGB IEC61966-2.1"
                    outputConditionIdentifier = "sRGB IEC61966-2.1"
                    registryName = "http://www.color.org"
                },
            )
            ByteArrayOutputStream().also(doc::save).toByteArray()
        }
}
