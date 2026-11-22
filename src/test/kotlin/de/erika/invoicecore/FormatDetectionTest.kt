package de.erika.invoicecore

import de.erika.invoicecore.model.InvoiceFormat
import de.erika.invoicecore.model.NotEInvoiceReason
import de.erika.invoicecore.model.Profile
import de.erika.invoicecore.model.Syntax
import de.erika.invoicecore.support.Fixtures
import de.erika.invoicecore.support.ModelDump
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatDetectionTest {
    private val cii = Fixtures.xrechnung("01.01a-INVOICE_uncefact.xml").readBytes()

    @Test
    fun `profiles are derived from the specification identifier`() {
        val cases =
            mapOf(
                "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0" to Profile.XRECHNUNG,
                "urn:cen.eu:en16931:2017" to Profile.EN16931,
                "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0" to Profile.EN16931,
                "urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:basic" to Profile.BASIC,
                "urn:cen.eu:en16931:2017#compliant#urn:zugferd.de:2p0:basic" to Profile.BASIC,
                "urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended" to Profile.EXTENDED,
                "urn:factur-x.eu:1p0:basicwl" to Profile.BASIC_WL,
                "urn:factur-x.eu:1p0:minimum" to Profile.MINIMUM,
                "urn:zugferd.de:2p0:minimum" to Profile.MINIMUM,
                "something else" to Profile.UNKNOWN,
            )
        cases.forEach { (spec, profile) -> assertEquals(profile, InvoiceParser.profileOf(spec), spec) }
        assertEquals(Profile.UNKNOWN, InvoiceParser.profileOf(null))
        assertFalse(Profile.MINIMUM.countsAsEInvoice)
        assertFalse(Profile.BASIC_WL.countsAsEInvoice)
        assertTrue(Profile.BASIC.countsAsEInvoice)
    }

    @Test
    fun `CII XML embedded in a PDF is a ZUGFeRD hybrid with the same content`() {
        val parsed = InvoiceParser.parse(pdf(attachments = mapOf("factur-x.xml" to cii)))
        assertEquals(InvoiceFormat.ZUGFERD, parsed.format.format)
        assertEquals(Syntax.CII, parsed.format.syntax)
        assertEquals(Profile.XRECHNUNG, parsed.format.profile)
        assertEquals("factur-x.xml", parsed.format.embeddedFileName)
        assertTrue(parsed.format.isEInvoice)
        assertEquals(ModelDump.render(InvoiceParser.parse(cii).invoice), ModelDump.render(parsed.invoice))
    }

    @Test
    fun `standard file names win over other attachments, any XML is the fallback`() {
        val other = "<note/>".toByteArray()
        val preferred = InvoiceParser.parse(pdf(attachments = mapOf("aaa.xml" to other, "zugferd-invoice.xml" to cii)))
        assertEquals("zugferd-invoice.xml", preferred.format.embeddedFileName)
        val fallback = InvoiceParser.parse(pdf(attachments = mapOf("rechnung.xml" to cii, "logo.png" to byteArrayOf(1, 2))))
        assertEquals("rechnung.xml", fallback.format.embeddedFileName)
        assertEquals(InvoiceFormat.ZUGFERD, fallback.format.format)
    }

    @Test
    fun `PDF without invoice XML is PDF_ONLY (IN-04)`() {
        listOf(pdf(attachments = emptyMap()), pdf(attachments = mapOf("logo.png" to byteArrayOf(1, 2)))).forEach { bytes ->
            val format = InvoiceParser.detect(bytes)
            assertEquals(InvoiceFormat.PDF_ONLY, format.format)
            assertEquals(NotEInvoiceReason.PLAIN_PDF, format.reason)
            assertFalse(format.isEInvoice)
        }
    }

    @Test
    fun `password-protected PDF can't be read`() {
        val e =
            assertFailsWith<InvoiceParseException> {
                InvoiceParser.parse(
                    pdf(attachments = mapOf("factur-x.xml" to cii), userPassword = "geheim"),
                )
            }
        assertEquals(ParseErrorCode.MALFORMED_PDF, e.code)
    }

    @Test
    fun `MINIMUM profile is read but not an e-invoice`() {
        val minimum =
            String(cii).replace("urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0", "urn:factur-x.eu:1p0:minimum")
        val parsed = InvoiceParser.parse(minimum.toByteArray())
        assertEquals(InvoiceFormat.CII, parsed.format.format)
        assertEquals(NotEInvoiceReason.INSUFFICIENT_PROFILE, parsed.format.reason)
        assertFalse(parsed.format.isEInvoice)
        val customSpec = String(cii).replace("urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0", "custom")
        val unknown = InvoiceParser.parse(customSpec.toByteArray())
        assertEquals(NotEInvoiceReason.UNKNOWN_PROFILE, unknown.format.reason)
    }

    private fun pdf(
        attachments: Map<String, ByteArray>,
        userPassword: String? = null,
    ): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            if (attachments.isNotEmpty()) {
                val specs =
                    attachments.mapValues { (name, bytes) ->
                        PDComplexFileSpecification().apply {
                            file = name
                            fileUnicode = name
                            embeddedFile = PDEmbeddedFile(doc, bytes.inputStream()).apply { size = bytes.size }
                            embeddedFileUnicode = embeddedFile
                        }
                    }
                val tree = PDEmbeddedFilesNameTreeNode().apply { names = specs }
                doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = tree }
            }
            if (userPassword != null) {
                doc.protect(StandardProtectionPolicy("owner", userPassword, AccessPermission()).apply { encryptionKeyLength = 128 })
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
}
