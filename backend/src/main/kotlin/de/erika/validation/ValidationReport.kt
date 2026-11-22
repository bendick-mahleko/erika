package de.erika.validation

import java.time.Instant

/** Overall result (VW-03): ERROR = not a valid e-invoice, WARNING = valid with remarks. */
enum class ValidationLevel { VALID, WARNING, ERROR }

enum class MessageSeverity { ERROR, WARNING, INFO }

/** Where a message comes from. */
enum class MessageSource {
    /** The file couldn't be read or isn't a supported e-invoice. */
    FORMAT,

    /** XML Schema (well-formedness and structure). */
    XML_SCHEMA,

    /** Schematron business rules (EN 16931, XRechnung). */
    SCHEMATRON,

    /** PDF/A-3 conformance of a ZUGFeRD/Factur-X PDF (veraPDF). */
    PDFA,

    /** ERIKA's content checks from invoice-core (arithmetic, § 14 UStG). */
    CONTENT,
}

data class ValidationMessage(
    /** Stable rule ID, e.g. `BR-CO-10`, `BR-DE-15`, `ERIKA-ARITH-03`, `XSD`. */
    val ruleId: String,
    val severity: MessageSeverity,
    val source: MessageSource,
    /** Plain-language German text (VW-04). */
    val text: String,
    /** The rule's own text as published (mostly English), for experts and the tax advisor. */
    val originalText: String? = null,
    /** XPath of the element concerned, if known. Never contains invoice content. */
    val location: String? = null,
)

/**
 * Result of validating one file with one validation pack (BR-07: [packId] is stored with
 * every result, so an audit can see which rules were applied).
 */
data class ValidationReport(
    val level: ValidationLevel,
    /** False if the file is not something that can be validated as an e-invoice (plain PDF, MINIMUM …). */
    val applicable: Boolean,
    val packId: String,
    /** Format and profile as detected by invoice-core, e.g. `XRECHNUNG_UBL` / `XRECHNUNG`. */
    val format: String?,
    val profile: String?,
    /** KoSIT scenario that was applied, e.g. "EN16931 XRechnung (UBL Invoice)". */
    val scenario: String?,
    /** KoSIT's recommendation for the XML part: true = accept. Null when KoSIT didn't run. */
    val kositAccepted: Boolean?,
    val messages: List<ValidationMessage>,
    val checkedAt: Instant,
    val durationMillis: Long,
) {
    val errors get() = messages.filter { it.severity == MessageSeverity.ERROR }
    val warnings get() = messages.filter { it.severity == MessageSeverity.WARNING }

    companion object {
        fun levelOf(messages: List<ValidationMessage>): ValidationLevel =
            when {
                messages.any { it.severity == MessageSeverity.ERROR } -> ValidationLevel.ERROR
                messages.any { it.severity == MessageSeverity.WARNING } -> ValidationLevel.WARNING
                else -> ValidationLevel.VALID
            }
    }
}
