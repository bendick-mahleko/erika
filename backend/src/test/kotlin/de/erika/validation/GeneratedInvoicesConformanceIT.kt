package de.erika.validation

import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.write.InvoiceWriter
import de.erika.invoicecore.write.OutputFormat
import de.erika.invoicecore.write.VatCase
import de.erika.support.CreatedInvoices
import de.erika.support.WorkerIntegrationTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
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
        }
}
