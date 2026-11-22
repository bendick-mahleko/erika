package de.erika.invoicecore.check

import de.erika.invoicecore.model.Address
import de.erika.invoicecore.model.Invoice
import java.math.BigDecimal

/**
 * Mandatory content of an invoice under § 14 Abs. 4 UStG (and §§ 14a UStG,
 * 33 UStDV), checked on the neutral model, separately from the format check (VW-05).
 *
 * Small-amount invoices (gross ≤ 250 EUR, § 33 UStDV) need less: no invoice
 * number, no buyer, no seller tax number.
 */
public object MandatoryContentChecks {
    public const val INVOICE_NUMBER: String = "ERIKA-USTG-01"
    public const val ISSUE_DATE: String = "ERIKA-USTG-02"
    public const val SELLER_NAME: String = "ERIKA-USTG-03"
    public const val SELLER_ADDRESS: String = "ERIKA-USTG-04"
    public const val BUYER_NAME_ADDRESS: String = "ERIKA-USTG-05"
    public const val SELLER_TAX_ID: String = "ERIKA-USTG-06"
    public const val DELIVERY_DATE: String = "ERIKA-USTG-07"
    public const val LINE_DESCRIPTION: String = "ERIKA-USTG-08"
    public const val LINE_QUANTITY: String = "ERIKA-USTG-09"
    public const val VAT_BREAKDOWN: String = "ERIKA-USTG-10"
    public const val EXEMPTION_REASON: String = "ERIKA-USTG-11"
    public const val REVERSE_CHARGE_BUYER_VAT_ID: String = "ERIKA-USTG-12"
    public const val INTRA_EU_VAT_IDS: String = "ERIKA-USTG-13"
    public const val SELF_BILLING_NOTE: String = "ERIKA-USTG-14"

    /** Upper limit for small-amount invoices (§ 33 UStDV, since 2017). */
    public val SMALL_AMOUNT_LIMIT: BigDecimal = BigDecimal("250.00")

    /** VAT categories that need an exemption reason (text or code) on the invoice. */
    private val NEEDS_EXEMPTION_REASON = setOf("E", "AE", "K", "G", "O")

    public fun isSmallAmountInvoice(invoice: Invoice): Boolean =
        invoice.currency == "EUR" && invoice.totals.totalWithVat?.let { it <= SMALL_AMOUNT_LIMIT } == true

    public fun check(invoice: Invoice): List<Finding> {
        val small = isSmallAmountInvoice(invoice)
        val f = mutableListOf<Finding>()

        fun error(
            rule: String,
            vararg bts: String,
            lineId: String? = null,
            params: Map<String, String> = emptyMap(),
        ) {
            f += Finding(rule, Severity.ERROR, bts.toList(), lineId, params)
        }

        if (!small && invoice.number == null) error(INVOICE_NUMBER, "BT-1")
        if (invoice.issueDate == null) error(ISSUE_DATE, "BT-2")
        if (invoice.seller.name == null) error(SELLER_NAME, "BT-27")
        if (!complete(invoice.seller.address)) error(SELLER_ADDRESS, "BG-5")
        if (!small && (invoice.buyer.name == null || !complete(invoice.buyer.address))) error(BUYER_NAME_ADDRESS, "BT-44", "BG-8")
        // Supplies outside the scope of VAT (category O only) need no tax number; EN 16931 BR-O-02 even forbids the VAT ID.
        val outsideScope = invoice.vatBreakdown.isNotEmpty() && invoice.vatBreakdown.all { it.categoryCode == "O" }
        val hasTaxId =
            invoice.seller.vatId != null || invoice.seller.taxRegistrationId != null || invoice.sellerTaxRepresentative?.vatId != null
        if (!small && !outsideScope && !hasTaxId) {
            error(SELLER_TAX_ID, "BT-31", "BT-32", "BT-63")
        }

        val d = invoice.delivery
        val linePeriods = invoice.lines.isNotEmpty() && invoice.lines.all { it.period != null }
        if (d?.actualDeliveryDate == null && d?.invoicingPeriod == null && !linePeriods) {
            // The date may be stated in a note, or equal the invoice date if the invoice says so: hence a warning.
            f += Finding(DELIVERY_DATE, Severity.WARNING, listOf("BT-72", "BG-14"))
        }

        invoice.lines.forEach { line ->
            if (line.item.name == null && line.item.description == null) error(LINE_DESCRIPTION, "BT-153", lineId = line.id)
            if (line.quantity == null) error(LINE_QUANTITY, "BT-129", lineId = line.id)
        }

        if (invoice.vatBreakdown.isEmpty()) error(VAT_BREAKDOWN, "BG-23")
        invoice.vatBreakdown.forEach { vat ->
            val code = vat.categoryCode ?: return@forEach
            if (code in NEEDS_EXEMPTION_REASON && vat.exemptionReason == null && vat.exemptionReasonCode == null) {
                error(EXEMPTION_REASON, "BT-120", "BT-121", params = mapOf("category" to code))
            }
        }

        val categories = invoice.vatBreakdown.mapNotNull { it.categoryCode }.toSet()
        if ("AE" in categories && invoice.buyer.vatId == null) error(REVERSE_CHARGE_BUYER_VAT_ID, "BT-48")
        if ("K" in categories && (invoice.seller.vatId == null || invoice.buyer.vatId == null)) error(INTRA_EU_VAT_IDS, "BT-31", "BT-48")

        if (invoice.typeCode == Invoice.TYPE_SELF_BILLED_INVOICE &&
            invoice.notes.none { it.text.contains("Gutschrift", ignoreCase = true) }
        ) {
            f += Finding(SELF_BILLING_NOTE, Severity.WARNING, listOf("BT-3", "BT-22"))
        }
        return f
    }

    private fun complete(address: Address?): Boolean =
        address != null && address.city != null && address.countryCode != null && (address.line1 != null || address.postCode != null)
}
