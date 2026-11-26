package de.erika.validation

import de.erika.invoicecore.model.Attachment
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.SupportingDocument
import de.erika.invoicecore.write.InvoiceWriter
import de.erika.invoicecore.write.OutputFormat
import de.erika.invoicecore.write.PdfLayout
import de.erika.invoicecore.write.VatCase
import de.erika.support.CreatedInvoices
import de.erika.support.WorkerIntegrationTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CR-02 / MVP acceptance item 3: every file invoice-core's writer produces passes VW-03 (KoSIT
 * XRechnung or EN 16931 scenario, veraPDF for the PDF/A-3 part, ERIKA's content checks) with
 * no errors, for all formats, VAT cases and document types.
 */
class GeneratedInvoicesConformanceIT : WorkerIntegrationTest() {
    @Autowired lateinit var validator: InvoiceValidator

    @TestFactory
    fun `generated invoices pass the official validation`(): List<DynamicTest> =
        OutputFormat.entries.flatMap { format ->
            VatCase.entries.flatMap { case ->
                listOf(Invoice.TYPE_COMMERCIAL_INVOICE, Invoice.TYPE_CREDIT_NOTE, Invoice.TYPE_CORRECTED_INVOICE).map { type ->
                    dynamicTest("$format / $case / $type") {
                        val bytes = InvoiceWriter.write(CreatedInvoices.invoice(case, type), format)
                        val report = validator.validate(bytes)
                        assertTrue(
                            report.errors.isEmpty(),
                            report.errors.joinToString("\n") { "${it.ruleId}: ${it.originalText ?: it.text}" },
                        )
                        assertTrue(report.applicable)
                        assertEquals(true, report.kositAccepted, report.messages.toString())
                        val expectedScenario =
                            when (format) {
                                OutputFormat.XRECHNUNG_CII -> {
                                    "EN16931 XRechnung (CII)"
                                }

                                OutputFormat.XRECHNUNG_UBL -> {
                                    if (type ==
                                        Invoice.TYPE_CREDIT_NOTE
                                    ) {
                                        "EN16931 XRechnung (UBL CreditNote)"
                                    } else {
                                        "EN16931 XRechnung (UBL Invoice)"
                                    }
                                }

                                OutputFormat.ZUGFERD_EN16931 -> {
                                    null
                                }
                            }
                        if (expectedScenario != null) assertEquals(expectedScenario, report.scenario)
                    }
                }
            }
        } + logoVariants() + attachmentVariants()

    /** CR-08: a logo (PNG with transparency, JPEG) and an accent colour keep the PDF/A-3 valid. */
    private fun logoVariants(): List<DynamicTest> =
        listOf("png" to BufferedImage.TYPE_INT_ARGB, "jpg" to BufferedImage.TYPE_INT_RGB).map { (type, imageType) ->
            dynamicTest("ZUGFERD_EN16931 with a $type logo and accent colour") {
                val layout = PdfLayout(image(type, imageType), "#1A5FB4")
                val bytes =
                    InvoiceWriter.write(
                        CreatedInvoices.invoice(VatCase.STANDARD_19, Invoice.TYPE_COMMERCIAL_INVOICE),
                        OutputFormat.ZUGFERD_EN16931,
                        layout,
                    )
                val report = validator.validate(bytes)
                assertTrue(report.errors.isEmpty(), report.errors.joinToString("\n") { "${it.ruleId}: ${it.originalText ?: it.text}" })
                assertEquals(true, report.kositAccepted, report.messages.toString())
            }
        }

    /** MARA Stage 8: a signed report PDF embedded as a supporting document (BG-24), for every format and VAT case. */
    private fun attachmentVariants(): List<DynamicTest> {
        val pdf =
            PDDocument().use { d ->
                d.addPage(PDPage())
                ByteArrayOutputStream().also { d.save(it) }.toByteArray()
            }
        return OutputFormat.entries.flatMap { format ->
            VatCase.entries.map { case ->
                dynamicTest("$format / $case with an embedded report PDF") {
                    val invoice =
                        CreatedInvoices.invoice(case).copy(
                            supportingDocuments =
                                listOf(
                                    SupportingDocument(
                                        reference = "ABCDEFGHJK",
                                        description = "Arbeitsbericht Therme warten",
                                        attachment = Attachment("application/pdf", "Arbeitsbericht-ABCDEFGHJK.pdf", pdf),
                                    ),
                                ),
                        )
                    val report = validator.validate(InvoiceWriter.write(invoice, format))
                    assertTrue(report.errors.isEmpty(), report.errors.joinToString("\n") { "${it.ruleId}: ${it.originalText ?: it.text}" })
                    assertEquals(true, report.kositAccepted, report.messages.toString())
                }
            }
        }
    }

    private fun image(
        format: String,
        imageType: Int,
    ): ByteArray {
        val img = BufferedImage(240, 80, imageType)
        img.createGraphics().apply {
            color = Color(0x1A, 0x5F, 0xB4, if (imageType == BufferedImage.TYPE_INT_ARGB) 160 else 255)
            fillRect(10, 10, 220, 60)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
    }
}
