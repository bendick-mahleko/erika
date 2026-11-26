package de.erika.reports

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Aufmaß calculations (M-MO-06). The same rules exist in Dart in the app; both must pass the
 * shared vectors in `mara-rules/vectors/aufmass/` (ADR 0036). Decimal arithmetic only: binary
 * floating point gives 1.1 × 1.1 = 1.2100000000000002.
 */
internal object AufmassRules {
    /** Results are rounded half away from zero to this many decimals. */
    const val SCALE = 3

    /** Every division is rounded half away from zero to this many decimals before the next step. */
    const val DIVISION_SCALE = 10

    /** Version of the position and formula rules; stored with each position. */
    const val VERSION = 1

    const val MAX_FORMULA = 200

    /** Area of a rectangle: length × width. Dimensions must not be negative. */
    fun area(
        length: BigDecimal,
        width: BigDecimal,
    ): BigDecimal {
        if (length.signum() < 0 || width.signum() < 0) throw AufmassException("NEGATIVE_DIMENSION")
        return length.multiply(width).setScale(SCALE, RoundingMode.HALF_UP)
    }

    /**
     * One position: LENGTH, AREA, VOLUME or PIECE times count, minus its deductions (each line
     * rounded first), or a FORMULA (vectors `aufmass/position.json`).
     */
    fun position(p: AufmassInput): BigDecimal {
        if (p.kind == "FORMULA") {
            if (p.deductions.isNotEmpty()) throw AufmassException("DEDUCTION_NOT_ALLOWED")
            return formula(p.expression ?: "")
        }
        val dims = DIMENSIONS[p.kind] ?: throw AufmassException("UNKNOWN_KIND")
        if (p.kind == "PIECE" && p.deductions.isNotEmpty()) throw AufmassException("DEDUCTION_NOT_ALLOWED")
        val main = line(dims, p.length, p.width, p.height, p.count)
        val deducted = p.deductions.fold(BigDecimal.ZERO) { sum, d -> sum + line(dims, d.length, d.width, d.height, d.count) }
        val result = main - deducted
        if (result.signum() < 0) throw AufmassException("NEGATIVE_RESULT")
        return result
    }

    private val DIMENSIONS = mapOf("LENGTH" to 1, "AREA" to 2, "VOLUME" to 3, "PIECE" to 0)

    private fun line(
        dims: Int,
        length: BigDecimal?,
        width: BigDecimal?,
        height: BigDecimal?,
        count: BigDecimal?,
    ): BigDecimal {
        val values = listOf(length, width, height).take(dims)
        if (values.any { it == null }) throw AufmassException("MISSING_DIMENSION")
        val factor = count ?: BigDecimal.ONE
        if (factor.signum() < 0 || values.any { it!!.signum() < 0 }) throw AufmassException("NEGATIVE_DIMENSION")
        return values.fold(factor) { product, v -> product.multiply(v) }.setScale(SCALE, RoundingMode.HALF_UP)
    }

    /** A simple formula, e.g. `2*(4,25+3,10)*2,5` (vectors `aufmass/formula.json`). */
    fun formula(expression: String): BigDecimal {
        if (expression.length > MAX_FORMULA || expression.isBlank()) throw AufmassException("INVALID_FORMULA")
        val parser = FormulaParser(tokenize(expression))
        val value = parser.expression()
        if (!parser.atEnd()) throw AufmassException("INVALID_FORMULA")
        val result = value.setScale(SCALE, RoundingMode.HALF_UP)
        if (result.signum() < 0) throw AufmassException("NEGATIVE_RESULT")
        return result
    }

    private val TOKEN = Regex("""\d+(?:[.,]\d+)?|[-+*/×÷()]|\S""")
    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

    private fun tokenize(text: String): List<String> = TOKEN.findAll(text).map { it.value }.toList()

    private class FormulaParser(
        private val tokens: List<String>,
    ) {
        private var pos = 0

        fun atEnd() = pos >= tokens.size

        private fun peek() = tokens.getOrNull(pos)

        private fun take() = tokens.getOrNull(pos++)

        fun expression(): BigDecimal {
            var value = term()
            while (peek() == "+" || peek() == "-") {
                val op = take()
                val right = term()
                value = if (op == "+") value + right else value - right
            }
            return value
        }

        private fun term(): BigDecimal {
            var value = factor()
            while (peek() in setOf("*", "/", "×", "÷")) {
                val op = take()
                val right = factor()
                value =
                    if (op == "*" || op == "×") {
                        value.multiply(right)
                    } else {
                        if (right.signum() == 0) throw AufmassException("DIVISION_BY_ZERO")
                        value.divide(right, DIVISION_SCALE, RoundingMode.HALF_UP)
                    }
            }
            return value
        }

        private fun factor(): BigDecimal {
            val token = take()
            return when {
                token == "-" -> {
                    factor().negate()
                }

                token == "(" -> {
                    val value = expression()
                    if (take() != ")") throw AufmassException("INVALID_FORMULA")
                    value
                }

                token != null && NUMBER.matches(token) -> {
                    BigDecimal(token.replace(',', '.'))
                }

                else -> {
                    throw AufmassException("INVALID_FORMULA")
                }
            }
        }
    }
}

/** A deduction from a position: the position's dimensions, times count. */
internal data class AufmassDeductionInput(
    val length: BigDecimal? = null,
    val width: BigDecimal? = null,
    val height: BigDecimal? = null,
    val count: BigDecimal? = null,
)

internal data class AufmassInput(
    val kind: String,
    val length: BigDecimal? = null,
    val width: BigDecimal? = null,
    val height: BigDecimal? = null,
    val count: BigDecimal? = null,
    val expression: String? = null,
    val deductions: List<AufmassDeductionInput> = emptyList(),
)

/** A rule violation; [code] is the stable code the shared vectors expect. */
internal class AufmassException(
    val code: String,
) : IllegalArgumentException(code)

/**
 * IDs two phones compute alike without talking to each other (vectors `ids/derived.json`): a
 * UUIDv8 from SHA-256(namespace bytes ‖ UTF-8 name). Two technicians who both start a work
 * order's report offline, or answer the same checklist item, write to the same record.
 */
internal object DerivedIds {
    fun of(
        namespace: UUID,
        name: String,
    ): UUID {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(
            ByteBuffer
                .allocate(16)
                .putLong(namespace.mostSignificantBits)
                .putLong(namespace.leastSignificantBits)
                .array(),
        )
        val bytes = digest.digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        bytes[6] = (0x80 or (bytes[6].toInt() and 0x0f)).toByte()
        bytes[8] = (0x80 or (bytes[8].toInt() and 0x3f)).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    /** The report of a work order, version [version]. */
    fun report(
        workOrderId: UUID,
        version: Int = 1,
    ): UUID = of(workOrderId, "report/$version")

    /** The answer to checklist item [key] of a report. */
    fun checklistAnswer(
        reportId: UUID,
        key: String,
    ): UUID = of(reportId, "checklist/$key")
}
