package de.erika.invoicecore.check

import de.erika.invoicecore.model.AllowanceCharge
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.InvoiceLine
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Arithmetic checks following the EN 16931 calculation rules (BR-CO-10 to
 * BR-CO-17, BR-S-08 and siblings), with rounding per BR-05: amounts are
 * compared after rounding the computed value to two decimals.
 *
 * A check only runs when the values it compares are present; missing mandatory
 * amounts are reported by the format validation.
 */
public object ArithmeticChecks {
    public const val LINE_NET_TOTAL: String = "ERIKA-ARITH-01"
    public const val ALLOWANCE_TOTAL: String = "ERIKA-ARITH-02"
    public const val CHARGE_TOTAL: String = "ERIKA-ARITH-03"
    public const val TOTAL_WITHOUT_VAT: String = "ERIKA-ARITH-04"
    public const val TOTAL_VAT: String = "ERIKA-ARITH-05"
    public const val TOTAL_WITH_VAT: String = "ERIKA-ARITH-06"
    public const val AMOUNT_DUE: String = "ERIKA-ARITH-07"
    public const val VAT_AMOUNT: String = "ERIKA-ARITH-08"
    public const val TAXABLE_AMOUNT: String = "ERIKA-ARITH-09"
    public const val LINE_NET_AMOUNT: String = "ERIKA-ARITH-10"
    public const val NET_PRICE: String = "ERIKA-ARITH-11"
    public const val MISSING_VAT_BREAKDOWN: String = "ERIKA-ARITH-12"
    public const val ALLOWANCE_PERCENTAGE: String = "ERIKA-ARITH-13"

    private val CENT = BigDecimal("0.01")

    /** VAT per category may deviate by up to one currency unit through line-level rounding before it's an error. */
    private val VAT_ERROR_TOLERANCE = BigDecimal.ONE

    public fun check(invoice: Invoice): List<Finding> {
        val findings = mutableListOf<Finding>()
        totals(invoice, findings)
        vatBreakdown(invoice, findings)
        invoice.lines.forEach { lineChecks(it, findings) }
        (invoice.allowances + invoice.charges).forEach { percentage(it, null, findings) }
        return findings
    }

    private fun totals(
        invoice: Invoice,
        findings: MutableList<Finding>,
    ) {
        val t = invoice.totals
        val lineSum = sum(invoice.lines.map { it.netAmount })
        val allowanceSum = sum(invoice.allowances.map { it.amount })
        val chargeSum = sum(invoice.charges.map { it.amount })

        if (invoice.lines.isNotEmpty()) equal(LINE_NET_TOTAL, listOf("BT-106", "BT-131"), t.lineNetTotal, lineSum, findings)
        if (t.allowanceTotal != null || invoice.allowances.isNotEmpty()) {
            equal(ALLOWANCE_TOTAL, listOf("BT-107", "BT-92"), t.allowanceTotal ?: BigDecimal.ZERO, allowanceSum, findings)
        }
        if (t.chargeTotal != null || invoice.charges.isNotEmpty()) {
            equal(CHARGE_TOTAL, listOf("BT-108", "BT-99"), t.chargeTotal ?: BigDecimal.ZERO, chargeSum, findings)
        }
        t.lineNetTotal?.let { net ->
            val expected = net - (t.allowanceTotal ?: BigDecimal.ZERO) + (t.chargeTotal ?: BigDecimal.ZERO)
            equal(TOTAL_WITHOUT_VAT, listOf("BT-109", "BT-106", "BT-107", "BT-108"), t.totalWithoutVat, expected, findings)
        }
        if (t.totalVat != null && invoice.vatBreakdown.isNotEmpty()) {
            equal(TOTAL_VAT, listOf("BT-110", "BT-117"), t.totalVat, sum(invoice.vatBreakdown.map { it.taxAmount }), findings)
        }
        t.totalWithoutVat?.let { base ->
            equal(TOTAL_WITH_VAT, listOf("BT-112", "BT-109", "BT-110"), t.totalWithVat, base + (t.totalVat ?: BigDecimal.ZERO), findings)
        }
        t.totalWithVat?.let { gross ->
            // XRechnung extension: third-party payments (BG-DEX-09) are added to the amount due.
            val expected =
                gross - (t.paidAmount ?: BigDecimal.ZERO) + (t.roundingAmount ?: BigDecimal.ZERO) +
                    sum(invoice.thirdPartyPayments.map { it.amount })
            equal(AMOUNT_DUE, listOf("BT-115", "BT-112", "BT-113", "BT-114"), t.amountDue, expected, findings)
        }
    }

    private fun vatBreakdown(
        invoice: Invoice,
        findings: MutableList<Finding>,
    ) {
        for (vat in invoice.vatBreakdown) {
            val category = vat.categoryCode ?: continue
            val key = CategoryKey.of(category, vat.rate)
            val params = mapOf("category" to key.label())

            // BT-117 = BT-116 × BT-119 / 100 (BR-CO-17); 0 for categories without VAT.
            if (vat.taxableAmount != null && vat.taxAmount != null) {
                val expected = round(vat.taxableAmount * (vat.rate ?: BigDecimal.ZERO) / BigDecimal(100))
                val diff = (vat.taxAmount - expected).abs()
                if (diff > CENT) {
                    findings +=
                        Finding(
                            VAT_AMOUNT,
                            if (diff > VAT_ERROR_TOLERANCE) Severity.ERROR else Severity.WARNING,
                            listOf("BT-117", "BT-116", "BT-119"),
                            params = params + amounts(expected, vat.taxAmount),
                        )
                }
            }

            // BT-116 = Σ line net + charges − allowances of this category (BR-S-08 etc.).
            if (vat.taxableAmount != null) {
                val lines = invoice.lines.filter { CategoryKey.of(it.vatCategoryCode, it.vatRate) == key }
                val charges = invoice.charges.filter { CategoryKey.of(it.vatCategoryCode, it.vatRate) == key }
                val allowances = invoice.allowances.filter { CategoryKey.of(it.vatCategoryCode, it.vatRate) == key }
                if (lines.isNotEmpty() || charges.isNotEmpty() || allowances.isNotEmpty()) {
                    val expected = sum(lines.map { it.netAmount }) + sum(charges.map { it.amount }) - sum(allowances.map { it.amount })
                    equal(TAXABLE_AMOUNT, listOf("BT-116", "BT-131", "BT-99", "BT-92"), vat.taxableAmount, expected, findings, params)
                }
            }
        }

        // Every VAT category used on a line or document allowance/charge needs a breakdown (BR-S-01 etc.).
        if (invoice.vatBreakdown.isNotEmpty()) {
            val declared = invoice.vatBreakdown.mapNotNull { v -> v.categoryCode?.let { CategoryKey.of(it, v.rate) } }.toSet()
            val used =
                (
                    invoice.lines.mapNotNull { l -> l.vatCategoryCode?.let { CategoryKey.of(it, l.vatRate) } } +
                        (invoice.allowances + invoice.charges).mapNotNull { a -> a.vatCategoryCode?.let { CategoryKey.of(it, a.vatRate) } }
                ).toSet()
            (used - declared).forEach {
                findings +=
                    Finding(MISSING_VAT_BREAKDOWN, Severity.ERROR, listOf("BG-23", "BT-151"), params = mapOf("category" to it.label()))
            }
        }
    }

    private fun lineChecks(
        line: InvoiceLine,
        findings: MutableList<Finding>,
    ) {
        val p = line.price
        val qty = line.quantity
        if (qty != null && p.netPrice != null && line.netAmount != null) {
            val base = p.baseQuantity?.takeIf { it.signum() != 0 } ?: BigDecimal.ONE
            val expected =
                qty * p.netPrice / base + sum(line.charges.map { it.amount }) - sum(line.allowances.map { it.amount })
            if ((line.netAmount - round(expected)).abs() > CENT) {
                findings +=
                    Finding(
                        LINE_NET_AMOUNT,
                        Severity.WARNING,
                        listOf("BT-131", "BT-129", "BT-146", "BT-149"),
                        line.id,
                        amounts(round(expected), line.netAmount),
                    )
            }
        }
        if (p.grossPrice != null && p.discount != null && p.netPrice != null && p.netPrice.compareTo(p.grossPrice - p.discount) != 0) {
            findings +=
                Finding(
                    NET_PRICE,
                    Severity.WARNING,
                    listOf("BT-146", "BT-148", "BT-147"),
                    line.id,
                    amounts(p.grossPrice - p.discount, p.netPrice),
                )
        }
        (line.allowances + line.charges).forEach { percentage(it, line.id, findings) }
    }

    private fun percentage(
        ac: AllowanceCharge,
        lineId: String?,
        findings: MutableList<Finding>,
    ) {
        if (ac.amount == null || ac.baseAmount == null || ac.percentage == null) return
        val expected = round(ac.baseAmount * ac.percentage / BigDecimal(100))
        if ((ac.amount - expected).abs() > CENT) {
            findings +=
                Finding(ALLOWANCE_PERCENTAGE, Severity.WARNING, listOf("BT-92", "BT-93", "BT-94"), lineId, amounts(expected, ac.amount))
        }
    }

    private fun equal(
        ruleId: String,
        terms: List<String>,
        actual: BigDecimal?,
        expected: BigDecimal,
        findings: MutableList<Finding>,
        extra: Map<String, String> = emptyMap(),
    ) {
        if (actual == null) return
        val rounded = round(expected)
        if (actual.compareTo(rounded) != 0) {
            findings += Finding(ruleId, Severity.ERROR, terms, params = extra + amounts(rounded, actual))
        }
    }

    private fun amounts(
        expected: BigDecimal,
        actual: BigDecimal,
    ) = mapOf("expected" to expected.setScale(2, RoundingMode.HALF_UP).toPlainString(), "actual" to actual.toPlainString())

    private fun sum(values: List<BigDecimal?>): BigDecimal = values.fold(BigDecimal.ZERO) { acc, v -> acc + (v ?: BigDecimal.ZERO) }

    private fun round(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    // BigDecimal division with enough precision for unit prices with many decimals.
    private operator fun BigDecimal.div(other: BigDecimal): BigDecimal = divide(other, 10, RoundingMode.HALF_UP)

    /** VAT category plus rate, with the rate compared numerically (19 == 19.00). */
    private data class CategoryKey(
        val code: String?,
        val rate: String?,
    ) {
        fun label(): String = if (rate == null) code.orEmpty() else "$code $rate %"

        companion object {
            fun of(
                code: String?,
                rate: BigDecimal?,
            ) = CategoryKey(code, rate?.stripTrailingZeros()?.toPlainString()?.takeUnless { code in NO_RATE_CATEGORIES && it == "0" })

            private val NO_RATE_CATEGORIES = setOf("O")
        }
    }
}
