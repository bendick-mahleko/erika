package de.erika.invoicecore.write

import de.erika.invoicecore.check.ArithmeticChecks
import de.erika.invoicecore.model.AllowanceCharge
import de.erika.invoicecore.model.DocumentTotals
import de.erika.invoicecore.model.PriceDetails
import de.erika.invoicecore.support.CreatedInvoices
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvoiceCalculatorTest {
    private fun d(s: String) = BigDecimal(s)

    @Test
    fun `totals and VAT per rate follow EN 16931`() {
        val invoice = InvoiceCalculator.complete(CreatedInvoices.invoice())
        // 8 × 95.00 = 760.00 (19 %), 3 × 24.99 = 74.97 (7 %), 1 × 49.90 = 49.90 (19 %)
        assertEquals(listOf(d("760.00"), d("74.97"), d("49.90")), invoice.lines.map { it.netAmount })
        val (s19, s7) = invoice.vatBreakdown
        assertEquals(d("809.90"), s19.taxableAmount)
        assertEquals(d("153.88"), s19.taxAmount)
        assertEquals(d("19.00"), s19.rate)
        assertEquals(d("74.97"), s7.taxableAmount)
        assertEquals(d("5.25"), s7.taxAmount)
        val t = invoice.totals
        assertEquals(d("884.87"), t.lineNetTotal)
        assertEquals(d("884.87"), t.totalWithoutVat)
        assertEquals(d("159.13"), t.totalVat)
        assertEquals(d("1044.00"), t.totalWithVat)
        assertEquals(d("1044.00"), t.amountDue)
        assertNull(t.allowanceTotal)
        assertTrue(ArithmeticChecks.check(invoice).isEmpty(), ArithmeticChecks.check(invoice).toString())
    }

    @Test
    fun `line amounts are rounded half up, base quantity divides the price`() {
        val line =
            CreatedInvoices
                .line("1", "Schrauben", null, "3", "C62", "0.125", VatCase.STANDARD_19)
                .copy(price = PriceDetails(netPrice = d("12.345"), baseQuantity = d("100")))
        assertEquals(d("0.37"), InvoiceCalculator.lineNet(line)) // 3 × 12.345 / 100 = 0.37035
        assertEquals(d("0.38"), InvoiceCalculator.round(d("0.375")))
    }

    @Test
    fun `document allowances and charges, prepaid amount and rounding`() {
        val base = CreatedInvoices.invoice(secondLine = null)
        val invoice =
            InvoiceCalculator.complete(
                base.copy(
                    allowances = listOf(AllowanceCharge(d("9.90"), vatCategoryCode = "S", vatRate = d("19"), reason = "Rabatt")),
                    charges = listOf(AllowanceCharge(d("5"), vatCategoryCode = "S", vatRate = d("19"), reason = "Verpackung")),
                    totals = DocumentTotals(paidAmount = d("100"), roundingAmount = d("0.01")),
                ),
            )
        val t = invoice.totals
        assertEquals(d("809.90"), t.lineNetTotal)
        assertEquals(d("9.90"), t.allowanceTotal)
        assertEquals(d("5.00"), t.chargeTotal)
        assertEquals(d("805.00"), t.totalWithoutVat)
        assertEquals(d("152.95"), t.totalVat)
        assertEquals(d("957.95"), t.totalWithVat)
        assertEquals(d("857.96"), t.amountDue)
        assertTrue(ArithmeticChecks.check(invoice).isEmpty(), ArithmeticChecks.check(invoice).toString())
    }

    @Test
    fun `VAT cases are found by category and rate`() {
        assertEquals(VatCase.REDUCED_7, VatCase.of("S", d("7.00")))
        assertEquals(VatCase.STANDARD_19, VatCase.of("S", null))
        assertEquals(VatCase.KLEINUNTERNEHMER, VatCase.of("E", d("0")))
        assertNull(VatCase.of("G", d("0")))
    }
}
