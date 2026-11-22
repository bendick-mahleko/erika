package de.erika.archive

import java.time.LocalDate

/**
 * Statutory retention (BR-02, § 14b UStG, § 147 AO as amended by BEG IV): 8 years from the
 * end of the calendar year in which the invoice was issued (or received, when the issue
 * date is unknown, e.g. a plain PDF or an unreadable file). Invoices dated before
 * 1 Jan 2025 follow the old 10-year rule.
 *
 * The result is the last day of retention (31 Dec), stored per document.
 */
object RetentionPolicy {
    /** First day of the 8-year rule. */
    val EIGHT_YEAR_RULE_FROM: LocalDate = LocalDate.of(2025, 1, 1)

    const val YEARS: Long = 8
    const val YEARS_BEFORE_2025: Long = 10

    fun retentionUntil(
        documentDate: LocalDate?,
        receivedOn: LocalDate,
    ): LocalDate {
        val base = documentDate ?: receivedOn
        val years = if (base.isBefore(EIGHT_YEAR_RULE_FROM)) YEARS_BEFORE_2025 else YEARS
        return LocalDate.of(base.year, 12, 31).plusYears(years)
    }
}
