package de.erika.invoicecore.check

import de.erika.invoicecore.model.Delivery
import de.erika.invoicecore.model.DocumentTotals
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.model.Note
import de.erika.invoicecore.model.Period
import de.erika.invoicecore.model.VatBreakdown
import de.erika.invoicecore.support.Fixtures
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import de.erika.invoicecore.check.MandatoryContentChecks as U

class MandatoryContentChecksTest {
    private val valid = Fixtures.standard

    private fun ids(invoice: Invoice) = U.check(invoice).map { it.ruleId }.toSet()

    @Test
    fun `a complete invoice passes`() {
        assertEquals(emptyList(), U.check(valid).filter { it.severity == Severity.ERROR })
    }

    @Test
    fun `missing number, date, seller and buyer data are errors`() {
        val broken =
            valid.copy(
                number = null,
                issueDate = null,
                seller = valid.seller.copy(name = null, address = null, vatId = null, taxRegistrationId = null),
                buyer = valid.buyer.copy(name = null),
            )
        val r = ids(broken)
        listOf(U.INVOICE_NUMBER, U.ISSUE_DATE, U.SELLER_NAME, U.SELLER_ADDRESS, U.BUYER_NAME_ADDRESS, U.SELLER_TAX_ID).forEach {
            assertTrue(it in r, "expected $it")
        }
    }

    @Test
    fun `small-amount invoices need no number, buyer or tax number (paragraph 33 UStDV)`() {
        val small =
            valid.copy(
                number = null,
                buyer = valid.buyer.copy(name = null, address = null),
                seller = valid.seller.copy(vatId = null, taxRegistrationId = null),
                totals = DocumentTotals(totalWithVat = BigDecimal("250.00")),
            )
        assertTrue(U.isSmallAmountInvoice(small))
        val r = ids(small)
        assertFalse(U.INVOICE_NUMBER in r)
        assertFalse(U.BUYER_NAME_ADDRESS in r)
        assertFalse(U.SELLER_TAX_ID in r)
        assertFalse(U.isSmallAmountInvoice(small.copy(totals = DocumentTotals(totalWithVat = BigDecimal("250.01")))))
        assertFalse(U.isSmallAmountInvoice(small.copy(currency = "CHF")))
    }

    @Test
    fun `no tax number needed when every VAT category is outside scope (O)`() {
        val noTaxId = valid.copy(seller = valid.seller.copy(vatId = null, taxRegistrationId = null))
        assertTrue(U.SELLER_TAX_ID in ids(noTaxId))
        val outside = VatBreakdown(BigDecimal("100.00"), BigDecimal.ZERO, "O", exemptionReasonCode = "VATEX-EU-O")
        assertFalse(U.SELLER_TAX_ID in ids(noTaxId.copy(vatBreakdown = listOf(outside))))
    }

    @Test
    fun `missing delivery date is a warning, satisfied by date, period or line periods`() {
        val none = valid.copy(delivery = null, lines = valid.lines.map { it.copy(period = null) })
        assertTrue(U.check(none).any { it.ruleId == U.DELIVERY_DATE && it.severity == Severity.WARNING })
        assertFalse(U.DELIVERY_DATE in ids(none.copy(delivery = Delivery(actualDeliveryDate = LocalDate.of(2026, 1, 1)))))
        assertFalse(U.DELIVERY_DATE in ids(none.copy(delivery = Delivery(invoicingPeriod = Period(LocalDate.of(2026, 1, 1))))))
        assertFalse(U.DELIVERY_DATE in ids(none.copy(lines = none.lines.map { it.copy(period = Period(LocalDate.of(2026, 1, 1))) })))
    }

    @Test
    fun `each line needs a description and a quantity`() {
        val line =
            valid.lines.first().copy(
                item =
                    valid.lines
                        .first()
                        .item
                        .copy(name = null, description = null),
                quantity = null,
            )
        val findings = U.check(valid.copy(lines = listOf(line)))
        assertTrue(findings.any { it.ruleId == U.LINE_DESCRIPTION && it.lineId == line.id })
        assertTrue(findings.any { it.ruleId == U.LINE_QUANTITY && it.lineId == line.id })
    }

    @Test
    fun `VAT breakdown and exemption reasons`() {
        assertTrue(U.VAT_BREAKDOWN in ids(valid.copy(vatBreakdown = emptyList())))
        val exempt = VatBreakdown(BigDecimal("100.00"), BigDecimal.ZERO, "E", BigDecimal.ZERO)
        assertTrue(U.EXEMPTION_REASON in ids(valid.copy(vatBreakdown = listOf(exempt))))
        assertFalse(U.EXEMPTION_REASON in ids(valid.copy(vatBreakdown = listOf(exempt.copy(exemptionReasonCode = "VATEX-EU-O")))))
    }

    @Test
    fun `reverse charge needs the buyer's VAT ID`() {
        val rc = Fixtures.reverseCharge
        assertFalse(U.REVERSE_CHARGE_BUYER_VAT_ID in ids(rc))
        assertTrue(U.REVERSE_CHARGE_BUYER_VAT_ID in ids(rc.copy(buyer = rc.buyer.copy(vatId = null))))
    }

    @Test
    fun `intra-EU supply needs both VAT IDs`() {
        val k = VatBreakdown(BigDecimal("100.00"), BigDecimal.ZERO, "K", BigDecimal.ZERO, exemptionReasonCode = "VATEX-EU-IC")
        val invoice = valid.copy(vatBreakdown = listOf(k), buyer = valid.buyer.copy(vatId = null))
        assertTrue(U.INTRA_EU_VAT_IDS in ids(invoice))
        assertFalse(
            U.INTRA_EU_VAT_IDS in
                ids(invoice.copy(buyer = valid.buyer.copy(vatId = "FR12345678901"), seller = valid.seller.copy(vatId = "DE123456789"))),
        )
    }

    @Test
    fun `self-billed invoice must say Gutschrift`() {
        val selfBilled = valid.copy(typeCode = Invoice.TYPE_SELF_BILLED_INVOICE, notes = emptyList())
        assertTrue(U.check(selfBilled).any { it.ruleId == U.SELF_BILLING_NOTE && it.severity == Severity.WARNING })
        assertFalse(U.SELF_BILLING_NOTE in ids(selfBilled.copy(notes = listOf(Note(text = "Gutschrift")))))
    }

    @Test
    fun `combined check runs both rule sets`() {
        val broken = valid.copy(number = null, totals = valid.totals.copy(amountDue = BigDecimal.ONE))
        val r = InvoiceChecks.check(broken).map { it.ruleId }
        assertTrue(U.INVOICE_NUMBER in r)
        assertTrue(ArithmeticChecks.AMOUNT_DUE in r)
    }
}
