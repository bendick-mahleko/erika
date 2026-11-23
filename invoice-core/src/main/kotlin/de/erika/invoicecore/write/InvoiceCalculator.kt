package de.erika.invoicecore.write

import de.erika.invoicecore.model.DocumentTotals
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.InvoiceLine
import de.erika.invoicecore.model.VatBreakdown
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Computes the amounts of an invoice from its lines, following EN 16931 (BR-05):
 * - line net amount (BT-131) = quantity × net price ÷ base quantity, rounded to 2 decimals;
 * - VAT breakdown (BG-23) per category and rate: taxable amount = sum of line net amounts
 *   plus document charges minus document allowances (BR-S-08, BR-AE-08 …); VAT amount =
 *   taxable amount × rate ÷ 100, rounded to 2 decimals (BR-CO-17);
 * - document totals (BG-22) from those (BR-CO-10 … BR-CO-16).
 *
 * Rounding is commercial (half up). Exemption reasons come from [VatCase].
 */
public object InvoiceCalculator {
    private val HUNDRED = BigDecimal("100")

    public fun round(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    /** Net amount of one line from quantity, price and base quantity. */
    public fun lineNet(line: InvoiceLine): BigDecimal {
        val quantity = line.quantity ?: BigDecimal.ZERO
        val price = line.price.netPrice ?: BigDecimal.ZERO
        val base = line.price.baseQuantity?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
        val gross = quantity.multiply(price).divide(base, 10, RoundingMode.HALF_UP)
        val allowances = line.allowances.sumOf { it.amount ?: BigDecimal.ZERO }
        val charges = line.charges.sumOf { it.amount ?: BigDecimal.ZERO }
        return round(gross.add(charges).subtract(allowances))
    }

    /** The invoice with line net amounts, VAT breakdown and totals (re)computed. */
    public fun complete(invoice: Invoice): Invoice {
        val lines = invoice.lines.map { it.copy(netAmount = lineNet(it)) }
        val lineTotal = round(lines.sumOf { it.netAmount!! })
        val allowanceTotal = invoice.allowances.sumOf { round(it.amount ?: BigDecimal.ZERO) }
        val chargeTotal = invoice.charges.sumOf { round(it.amount ?: BigDecimal.ZERO) }

        data class Key(
            val category: String,
            val rate: BigDecimal,
        )

        val taxable = linkedMapOf<Key, BigDecimal>()

        fun add(
            category: String?,
            rate: BigDecimal?,
            amount: BigDecimal,
        ) {
            val key = Key(category ?: "S", (rate ?: BigDecimal.ZERO).stripTrailingZeros())
            taxable[key] = (taxable[key] ?: BigDecimal.ZERO).add(amount)
        }
        lines.forEach { add(it.vatCategoryCode, it.vatRate, it.netAmount!!) }
        invoice.charges.forEach { add(it.vatCategoryCode, it.vatRate, round(it.amount ?: BigDecimal.ZERO)) }
        invoice.allowances.forEach { add(it.vatCategoryCode, it.vatRate, round(it.amount ?: BigDecimal.ZERO).negate()) }

        val breakdown =
            taxable.entries
                .sortedWith(compareBy<Map.Entry<Key, BigDecimal>> { it.key.category }.thenByDescending { it.key.rate })
                .map { (key, base) ->
                    val case = VatCase.of(key.category, key.rate)
                    val tax = if (key.category == "S") round(base.multiply(key.rate).divide(HUNDRED, 10, RoundingMode.HALF_UP)) else ZERO
                    VatBreakdown(
                        taxableAmount = round(base),
                        taxAmount = tax,
                        categoryCode = key.category,
                        rate = key.rate.setScale(2),
                        exemptionReason = case?.exemptionReason,
                        exemptionReasonCode = case?.exemptionReasonCode,
                    )
                }
        val totalVat = round(breakdown.sumOf { it.taxAmount!! })
        val withoutVat = round(lineTotal.subtract(allowanceTotal).add(chargeTotal))
        val withVat = round(withoutVat.add(totalVat))
        val paid = invoice.totals.paidAmount?.let(::round)
        val rounding = invoice.totals.roundingAmount?.let(::round)
        val due = round(withVat.subtract(paid ?: BigDecimal.ZERO).add(rounding ?: BigDecimal.ZERO))
        return invoice.copy(
            lines = lines,
            vatBreakdown = breakdown,
            totals =
                DocumentTotals(
                    lineNetTotal = lineTotal,
                    allowanceTotal = if (invoice.allowances.isEmpty()) null else round(allowanceTotal),
                    chargeTotal = if (invoice.charges.isEmpty()) null else round(chargeTotal),
                    totalWithoutVat = withoutVat,
                    totalVat = totalVat,
                    totalWithVat = withVat,
                    paidAmount = paid,
                    roundingAmount = rounding,
                    amountDue = due,
                ),
        )
    }

    private val ZERO = BigDecimal("0.00")
}
