package de.erika.invoicecore

import de.erika.invoicecore.check.InvoiceChecks
import de.erika.invoicecore.check.Severity
import de.erika.invoicecore.model.InvoiceFormat
import de.erika.invoicecore.model.NotEInvoiceReason
import de.erika.invoicecore.model.Profile
import de.erika.invoicecore.support.Golden
import de.erika.invoicecore.support.ModelDump
import de.erika.invoicecore.support.Suites
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 2 exit criteria: every file in the official test suites is detected and
 * parsed (IN-03), valid XRechnung test invoices pass the arithmetic checks
 * (VW-05), and parsed models match the reviewed golden files.
 */
class ConformanceTest {
    @TestFactory
    fun `XRechnung test suite - detected, parsed, arithmetic correct, matches golden file`(): List<DynamicTest> =
        Suites.files(Suites.xrechnung, "xml").map { file ->
            dynamicTest(Suites.relative(file)) {
                val parsed = InvoiceParser.parse(file.readBytes())
                val expected = if (file.name.contains("_ubl")) InvoiceFormat.XRECHNUNG_UBL else InvoiceFormat.XRECHNUNG_CII
                assertEquals(expected, parsed.format.format)
                assertEquals(Profile.XRECHNUNG, parsed.format.profile)
                assertTrue(parsed.format.isEInvoice)
                assertEquals(emptyList(), parsed.warnings, "parse warnings")
                val invoice = assertNotNull(parsed.invoice)
                assertNotNull(invoice.number)

                val errors = InvoiceChecks.check(invoice).filter { it.severity == Severity.ERROR }
                assertEquals(emptyList(), errors, "content errors on a valid test invoice")

                Golden.verify(goldenName(file), ModelDump.render(invoice))
            }
        }

    @TestFactory
    fun `EN 16931 examples - detected and parsed`(): List<DynamicTest> =
        (Suites.files(Suites.enUbl, "xml") + Suites.files(Suites.enCii, "xml")).map { file ->
            dynamicTest(Suites.relative(file)) {
                val parsed = InvoiceParser.parse(file.readBytes())
                val ubl = file.path.contains("en16931-ubl")
                val allowed =
                    if (ubl) {
                        setOf(
                            InvoiceFormat.UBL,
                            InvoiceFormat.XRECHNUNG_UBL,
                        )
                    } else {
                        setOf(InvoiceFormat.CII, InvoiceFormat.XRECHNUNG_CII)
                    }
                assertTrue(parsed.format.format in allowed, "format ${parsed.format.format}")
                Golden.verify(goldenName(file), ModelDump.render(parsed.invoice))
            }
        }

    @TestFactory
    fun `ZUGFeRD corpus - valid v2 files detected with their profile`(): List<DynamicTest> =
        Suites.files(Suites.corpus.resolve("ZUGFeRDv2/correct"), "pdf", "xml").map { file ->
            dynamicTest(file.relativeTo(Suites.corpus).invariantSeparatorsPath) {
                val parsed = InvoiceParser.parse(file.readBytes())
                val isPdf = file.extension.equals("pdf", ignoreCase = true)
                if (isPdf) {
                    assertEquals(InvoiceFormat.ZUGFERD, parsed.format.format)
                    assertNotNull(parsed.format.embeddedFileName)
                } else {
                    assertTrue(parsed.format.format in setOf(InvoiceFormat.CII, InvoiceFormat.XRECHNUNG_CII, InvoiceFormat.UBL))
                }
                assertNotNull(parsed.invoice)
                expectedProfile(file)?.let { assertEquals(it, parsed.format.profile, "profile") }
            }
        }

    @TestFactory
    fun `ZUGFeRD corpus - version 1 files are recognised as unsupported`(): List<DynamicTest> =
        Suites.files(Suites.corpus.resolve("ZUGFeRDv1/correct"), "pdf").map { file ->
            dynamicTest(file.name) {
                val parsed = InvoiceParser.parse(file.readBytes())
                assertEquals(InvoiceFormat.UNSUPPORTED, parsed.format.format)
                assertEquals(NotEInvoiceReason.ZUGFERD_1, parsed.format.reason)
                assertNull(parsed.invoice)
            }
        }

    @TestFactory
    fun `ZUGFeRD corpus - plain PDF and other XML invoices`(): List<DynamicTest> =
        listOf(
            dynamicTest("unstructured PDF is PDF_ONLY") {
                Suites.files(Suites.corpus.resolve("unstructured"), "pdf").forEach { file ->
                    val parsed = InvoiceParser.parse(file.readBytes())
                    assertEquals(InvoiceFormat.PDF_ONLY, parsed.format.format)
                    assertEquals(NotEInvoiceReason.PLAIN_PDF, parsed.format.reason)
                }
            },
        ) +
            listOf("UBL", "CII", "FX").flatMap { folder ->
                Suites.files(Suites.corpus.resolve("XML-Rechnung/$folder"), "xml", "pdf").map { file ->
                    dynamicTest("XML-Rechnung/$folder/${file.name}") {
                        val parsed = InvoiceParser.parse(file.readBytes())
                        val allowed =
                            when (folder) {
                                "UBL" -> setOf(InvoiceFormat.UBL, InvoiceFormat.XRECHNUNG_UBL)
                                "CII" -> setOf(InvoiceFormat.CII, InvoiceFormat.XRECHNUNG_CII)
                                else -> setOf(InvoiceFormat.ZUGFERD)
                            }
                        assertTrue(parsed.format.format in allowed, "format ${parsed.format.format}")
                        assertNotNull(parsed.invoice)
                    }
                }
            }

    private fun expectedProfile(file: File): Profile? {
        val path = file.invariantSeparatorsPath.uppercase()
        return when {
            "/INTARSYS/MINIMUM/" in path || "_MINIMUM." in path -> Profile.MINIMUM
            "/INTARSYS/BASIC/" in path || "_BASIC." in path -> Profile.BASIC
            "/INTARSYS/EN16931/" in path -> Profile.EN16931
            "/INTARSYS/EXTENDED/" in path -> Profile.EXTENDED
            "_BASICWL." in path -> Profile.BASIC_WL
            else -> null
        }
    }

    private fun goldenName(file: File): String = Suites.relative(file).substringBeforeLast('.')
}
