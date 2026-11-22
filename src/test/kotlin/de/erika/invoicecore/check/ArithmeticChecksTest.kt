package de.erika.invoicecore.check

import de.erika.invoicecore.model.AllowanceCharge
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.ThirdPartyPayment
import de.erika.invoicecore.model.VatBreakdown
import de.erika.invoicecore.support.Fixtures
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArithmeticChecksTest {
    private val valid = Fixtures.standard

    private fun rules(invoice: Invoice) = ArithmeticChecks.check(invoice).map { it.ruleId to it.severity }

    @Test
    fun `a valid invoice has no arithmetic findings`() {
        assertEquals(emptyList(), ArithmeticChecks.check(valid))
    }

    @Test
    fun `wrong total is flagged red (VW-05 acceptance)`() {
        val wrong = valid.copy(totals = valid.totals.copy(totalWithVat = valid.totals.totalWithVat!! + BigDecimal.ONE))
        val findings = ArithmeticChecks.check(wrong)
        assertTrue(findings.any { it.ruleId == ArithmeticChecks.TOTAL_WITH_VAT && it.severity == Severity.ERROR })
        val f = findings.first { it.ruleId == ArithmeticChecks.TOTAL_WITH_VAT }
        assertEquals(
            valid.totals.totalWithVat!!
                .setScale(2)
                .toPlainString(),
            f.params["expected"],
        )
    }

    @Test
    fun `sum of line net amounts must equal BT-106`() {
        val wrong = valid.copy(totals = valid.totals.copy(lineNetTotal = BigDecimal("1.00")))
        assertTrue(ArithmeticChecks.LINE_NET_TOTAL to Severity.ERROR in rules(wrong))
    }

    @Test
    fun `document allowances and charges must add up`() {
        val line = valid.lines.first()
        val allowance = AllowanceCharge(BigDecimal("10.00"), vatCategoryCode = line.vatCategoryCode, vatRate = line.vatRate)
        val wrong = valid.copy(allowances = listOf(allowance))
        val r = rules(wrong)
        assertTrue(ArithmeticChecks.ALLOWANCE_TOTAL to Severity.ERROR in r)
        assertTrue(ArithmeticChecks.TAXABLE_AMOUNT to Severity.ERROR in r, "findings: $r")
        assertTrue(ArithmeticChecks.CHARGE_TOTAL !in r.map { it.first })
        val withCharge = valid.copy(charges = listOf(allowance))
        assertTrue(ArithmeticChecks.CHARGE_TOTAL to Severity.ERROR in rules(withCharge))
    }

    @Test
    fun `total without VAT, total VAT and amount due are recomputed`() {
        val t = valid.totals
        assertTrue(
            ArithmeticChecks.TOTAL_WITHOUT_VAT to Severity.ERROR in rules(valid.copy(totals = t.copy(totalWithoutVat = BigDecimal.ONE))),
        )
        assertTrue(ArithmeticChecks.TOTAL_VAT to Severity.ERROR in rules(valid.copy(totals = t.copy(totalVat = BigDecimal.ONE))))
        assertTrue(ArithmeticChecks.AMOUNT_DUE to Severity.ERROR in rules(valid.copy(totals = t.copy(amountDue = BigDecimal.ONE))))
        // Prepaid amount and rounding are taken into account.
        val prepaid = t.copy(paidAmount = BigDecimal("10.00"), amountDue = t.amountDue!! - BigDecimal("10.00"))
        assertEquals(emptyList(), ArithmeticChecks.check(valid.copy(totals = prepaid)))
    }

    @Test
    fun `third-party payments (XRechnung extension) are added to the amount due`() {
        val t = valid.totals
        val invoice =
            valid.copy(
                totals = t.copy(amountDue = t.amountDue!! + BigDecimal("5.00")),
                thirdPartyPayments = listOf(ThirdPartyPayment("MobilesBezahlen", BigDecimal("5.00"))),
            )
        assertEquals(emptyList(), ArithmeticChecks.check(invoice))
    }

    @Test
    fun `VAT amount per rate - small deviation is amber, large deviation red`() {
        val vat = valid.vatBreakdown.single()

        fun withTax(delta: String) = valid.copy(vatBreakdown = listOf(vat.copy(taxAmount = vat.taxAmount!! + BigDecimal(delta))))
        assertTrue(ArithmeticChecks.VAT_AMOUNT to Severity.WARNING in rules(withTax("0.50")))
        assertTrue(ArithmeticChecks.VAT_AMOUNT to Severity.ERROR in rules(withTax("5.00")))
        assertTrue(ArithmeticChecks.VAT_AMOUNT !in rules(withTax("0.01")).map { it.first })
    }

    @Test
    fun `VAT category used on a line needs a breakdown`() {
        val line = valid.lines.first().copy(vatCategoryCode = "AA", vatRate = BigDecimal("7"))
        val r = rules(valid.copy(lines = listOf(line) + valid.lines.drop(1)))
        assertTrue(ArithmeticChecks.MISSING_VAT_BREAKDOWN to Severity.ERROR in r)
        val f = ArithmeticChecks.check(valid.copy(lines = listOf(line))).first { it.ruleId == ArithmeticChecks.MISSING_VAT_BREAKDOWN }
        assertEquals("AA 7 %", f.params["category"])
    }

    @Test
    fun `categories without rate compare correctly`() {
        val line = valid.lines.first().copy(vatCategoryCode = "O", vatRate = null)
        val vat = VatBreakdown(line.netAmount, BigDecimal.ZERO, "O", exemptionReason = "Nicht steuerbar")
        val invoice =
            valid.copy(
                lines = listOf(line),
                vatBreakdown = listOf(vat),
                totals =
                    valid.totals.copy(
                        lineNetTotal = line.netAmount,
                        totalWithoutVat = line.netAmount,
                        totalVat = BigDecimal.ZERO,
                        totalWithVat = line.netAmount,
                        amountDue = line.netAmount,
                    ),
            )
        assertEquals(emptyList(), ArithmeticChecks.check(invoice))
    }

    @Test
    fun `line amount, net price and percentages are plausibility warnings`() {
        val line = valid.lines.first()
        val wrongLine =
            line.copy(
                netAmount = line.netAmount!! + BigDecimal("3.00"),
                price = line.price.copy(grossPrice = BigDecimal("999"), discount = BigDecimal("1")),
                allowances = listOf(AllowanceCharge(BigDecimal("5.00"), BigDecimal("100.00"), BigDecimal("10"))),
            )
        val r = rules(valid.copy(lines = listOf(wrongLine) + valid.lines.drop(1)))
        assertTrue(ArithmeticChecks.LINE_NET_AMOUNT to Severity.WARNING in r)
        assertTrue(ArithmeticChecks.NET_PRICE to Severity.WARNING in r)
        assertTrue(ArithmeticChecks.ALLOWANCE_PERCENTAGE to Severity.WARNING in r)
        val docAllowance = AllowanceCharge(BigDecimal("5.00"), BigDecimal("100.00"), BigDecimal("10"), line.vatCategoryCode, line.vatRate)
        assertTrue(ArithmeticChecks.ALLOWANCE_PERCENTAGE to Severity.WARNING in rules(valid.copy(allowances = listOf(docAllowance))))
    }

    @Test
    fun `base quantity divides the price`() {
        val line = valid.lines.first()
        val per100 =
            line.copy(price = line.price.copy(netPrice = line.price.netPrice!! * BigDecimal(100), baseQuantity = BigDecimal(100)))
        assertTrue(ArithmeticChecks.LINE_NET_AMOUNT !in rules(valid.copy(lines = listOf(per100) + valid.lines.drop(1))).map { it.first })
    }

    @Test
    fun `missing amounts skip the check instead of failing it`() {
        val empty = Invoice(number = "1", issueDate = null, typeCode = "380", currency = "EUR")
        assertEquals(emptyList(), ArithmeticChecks.check(empty))
    }
}
