package de.erika.validation

import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.Foundries
import org.verapdf.pdfa.flavours.PDFAFlavour
import org.verapdf.pdfa.results.TestAssertion
import java.io.ByteArrayInputStream

/** A failed PDF/A requirement: ISO 19005 clause and test number, e.g. `6.2.11.4.1-1`. */
data class PdfaFailure(
    val rule: String,
    val description: String,
)

data class PdfaResult(
    val compliant: Boolean,
    /** Distinct failed rules (veraPDF reports every occurrence; one per rule is enough here). */
    val failures: List<PdfaFailure>,
    /** The file couldn't be parsed as PDF at all. */
    val unreadable: Boolean = false,
)

/**
 * PDF/A-3 conformance of the visual part of a ZUGFeRD/Factur-X invoice (ZUGFeRD requires
 * PDF/A-3), checked with veraPDF, the reference validator of the PDF Association.
 * Runs in the worker only (Software Design §7: PDF processing with memory and time limits).
 */
class PdfaCheck {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    init {
        VeraGreenfieldFoundryProvider.initialise()
    }

    fun check(pdf: ByteArray): PdfaResult =
        try {
            Foundries.defaultInstance().use { foundry ->
                // ZUGFeRD requires PDF/A-3; level B (visual integrity) is what every A-level file also meets.
                foundry.createParser(ByteArrayInputStream(pdf), PDFAFlavour.PDFA_3_B).use { parser ->
                    val validator = foundry.createValidator(PDFAFlavour.PDFA_3_B, false)
                    val result = validator.validate(parser)
                    val failures =
                        result.testAssertions
                            .filter { it.status == TestAssertion.Status.FAILED }
                            .map { PdfaFailure("${it.ruleId.clause}-${it.ruleId.testNumber}", it.message.orEmpty()) }
                            .distinctBy { it.rule }
                            .sortedBy { it.rule }
                    PdfaResult(result.isCompliant, failures)
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("PDF/A check could not read the PDF", e)
            PdfaResult(compliant = false, failures = emptyList(), unreadable = true)
        }
}
