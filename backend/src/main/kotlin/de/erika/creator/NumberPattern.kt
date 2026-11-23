package de.erika.creator

import java.time.LocalDate

/**
 * Invoice number pattern (CR-03), e.g. `RE-{YYYY}-{NNNN}`:
 * - `{YYYY}`, `{YY}`, `{MM}`: parts of the issue date,
 * - exactly one counter `{N…}`; the number of N is the minimum number of digits,
 * - other characters: letters, digits and `- _ / .`.
 *
 * The counter runs per [sequenceKey]: the pattern with the date parts filled in and the
 * counter normalised, so `RE-{YYYY}-{NNNN}` counts per year and changing the digits
 * doesn't restart it.
 */
class NumberPattern private constructor(
    val pattern: String,
) {
    private val counter = COUNTER.find(pattern)!!
    private val digits = counter.value.length - 2

    fun sequenceKey(date: LocalDate): String = withDate(date).replace(COUNTER, "{N}")

    fun format(
        date: LocalDate,
        value: Long,
    ): String = withDate(date).replace(COUNTER, value.toString().padStart(digits, '0'))

    private fun withDate(date: LocalDate): String =
        pattern
            .replace("{YYYY}", "%04d".format(date.year))
            .replace("{YY}", "%02d".format(date.year % 100))
            .replace("{MM}", "%02d".format(date.monthValue))

    companion object {
        private val COUNTER = Regex("\\{N{1,10}}")
        private val TOKEN = Regex("\\{[^}]*}")
        private val LITERAL = Regex("^[A-Za-z0-9_./-]*$")
        const val MAX_LENGTH = 40

        /** Null if the pattern is not valid. */
        fun parse(pattern: String): NumberPattern? {
            val p = pattern.trim()
            if (p.isEmpty() || p.length > MAX_LENGTH) return null
            if (COUNTER.findAll(p).count() != 1) return null
            val tokens = TOKEN.findAll(p).map { it.value }.toList()
            if (tokens.any { it !in setOf("{YYYY}", "{YY}", "{MM}") && !COUNTER.matches(it) }) return null
            if (!LITERAL.matches(TOKEN.replace(p, ""))) return null
            return NumberPattern(p)
        }
    }
}
