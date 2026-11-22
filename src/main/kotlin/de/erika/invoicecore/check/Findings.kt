package de.erika.invoicecore.check

import de.erika.invoicecore.model.Invoice

public enum class Severity {
    /** The invoice is wrong: shown red (VW-04). */
    ERROR,

    /** Worth checking, but not necessarily wrong: shown amber. */
    WARNING,
}

/**
 * Result of a content check. [params] holds values for the rule text (amounts,
 * line IDs, VAT categories), never personal data.
 */
public data class Finding(
    val ruleId: String,
    val severity: Severity,
    val businessTerms: List<String>,
    val lineId: String? = null,
    val params: Map<String, String> = emptyMap(),
) {
    /** Plain-language text in German (`de`, default) or English (`en`). */
    public fun message(language: String = "de"): String = RuleTexts.render(this, language)
}

/**
 * Content checks that complement the format validation (VW-05): arithmetic
 * (line sums, VAT per rate, totals) and the mandatory content of an invoice
 * under § 14 UStG. They run on the neutral model, so they apply equally to
 * UBL, CII and ZUGFeRD.
 */
public object InvoiceChecks {
    public fun check(invoice: Invoice): List<Finding> = ArithmeticChecks.check(invoice) + MandatoryContentChecks.check(invoice)
}
