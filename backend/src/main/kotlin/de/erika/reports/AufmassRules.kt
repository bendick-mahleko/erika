package de.erika.reports

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Aufmaß calculations (M-MO-06). The same rules exist in Dart in the app; both must pass the
 * shared vectors in `mara-rules/vectors/aufmass/` (ADR 0036). Decimal arithmetic only: binary
 * floating point gives 1.1 × 1.1 = 1.2100000000000002.
 */
internal object AufmassRules {
    /** Results are rounded half away from zero to this many decimals. */
    const val SCALE = 3

    /** Area of a rectangle: length × width. Dimensions must not be negative. */
    fun area(
        length: BigDecimal,
        width: BigDecimal,
    ): BigDecimal {
        if (length.signum() < 0 || width.signum() < 0) throw AufmassException("NEGATIVE_DIMENSION")
        return length.multiply(width).setScale(SCALE, RoundingMode.HALF_UP)
    }
}

/** A rule violation; [code] is the stable code the shared vectors expect. */
internal class AufmassException(
    val code: String,
) : IllegalArgumentException(code)
