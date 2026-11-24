package de.erika.invoicecore.write

import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.model.InvoiceFormat
import de.erika.invoicecore.support.CreatedInvoices
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CR-08: logo and accent colour on the readable PDF; the invoice data stays the same. */
class PdfLayoutTest {
    private fun image(
        format: String,
        alpha: Boolean = false,
    ): ByteArray {
        val img = BufferedImage(300, 100, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            color = Color(0x1F, 0x4E, 0x79)
            fillRect(10, 10, 280, 80)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }

    private fun images(pdf: ByteArray): Int =
        Loader.loadPDF(pdf).use { doc ->
            val resources = doc.getPage(0).resources
            resources.xObjectNames.count { resources.getXObject(it) is PDImageXObject }
        }

    @Test
    fun `a PNG or JPEG logo appears on the first page, and the invoice data is unchanged`() {
        val invoice = CreatedInvoices.invoice()
        val plain = InvoiceWriter.write(invoice, OutputFormat.ZUGFERD_EN16931)
        listOf(image("png", alpha = true), image("jpg")).forEach { logo ->
            val pdf = InvoiceWriter.write(invoice, OutputFormat.ZUGFERD_EN16931, PdfLayout(logo, "#1F4E79"))
            assertEquals(1, images(pdf))
            val parsed = InvoiceParser.parse(pdf)
            assertEquals(InvoiceFormat.ZUGFERD, parsed.format.format)
            assertEquals(InvoiceParser.parse(plain).invoice, parsed.invoice, "same embedded invoice")
            assertContentEquals(
                pdf,
                InvoiceWriter.write(invoice, OutputFormat.ZUGFERD_EN16931, PdfLayout(logo, "#1F4E79")),
                "deterministic",
            )
        }
        assertEquals(0, images(plain))
    }

    @Test
    fun `XRechnung ignores the layout`() {
        val invoice = CreatedInvoices.invoice()
        assertContentEquals(
            InvoiceWriter.write(invoice, OutputFormat.XRECHNUNG_CII),
            InvoiceWriter.write(invoice, OutputFormat.XRECHNUNG_CII, PdfLayout(image("png"), "#000000")),
        )
    }

    @Test
    fun `only PNG and JPEG up to the size limit, and only #RRGGBB colours`() {
        assertFailsWith<IllegalArgumentException> { PdfLayout("GIF89a....".toByteArray()) }
        assertFailsWith<IllegalArgumentException> {
            PdfLayout(
                ByteArray(PdfLayout.MAX_LOGO_BYTES + 1).also {
                    it[0] = 0xFF.toByte()
                    it[1] =
                        0xD8.toByte()
                    it[2] = 0xFF.toByte()
                },
            )
        }
        assertFailsWith<IllegalArgumentException> { PdfLayout(accentColor = "red") }
        assertEquals("image/png", PdfLayout.imageType(image("png")))
        assertEquals("image/jpeg", PdfLayout.imageType(image("jpg")))
        assertFalse(PdfLayout.imageType("<svg/>".toByteArray()) != null)
        assertTrue(PdfLayout(accentColor = "#a1B2c3").accentRgb!!.all { it in 0f..1f })
    }
}
