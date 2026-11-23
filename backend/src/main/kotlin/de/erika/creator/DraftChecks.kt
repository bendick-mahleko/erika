package de.erika.creator

import de.erika.identity.WorkspaceProfile
import java.math.BigDecimal

/**
 * Server-side checks of a draft before it may be issued (CR-01): the mandatory content of an
 * invoice under § 14 Abs. 4 UStG and EN 16931, plus what XRechnung and the VAT case need.
 * The front end runs the same checks for instant feedback; the server repeats every one
 * (Software Design §5, Flow C step 1). The official validation runs again on the generated
 * file when the invoice is issued.
 */
object DraftChecks {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val COUNTRY = Regex("^[A-Z]{2}$")
    private val UNIT = Regex("^[A-Z0-9]{1,3}$")
    const val MAX_LINES = 500

    fun problems(
        kind: InvoiceKind,
        content: DraftContent,
        settings: InvoiceSettings,
        seller: WorkspaceProfile,
    ): List<Problem> =
        buildList {
            val c = content

            fun required(
                field: String,
                value: String?,
            ) {
                if (value.isNullOrBlank()) add(Problem(field, "REQUIRED"))
            }

            if (c.format == null) add(Problem("format", "REQUIRED"))
            addAll(settings.problems(seller, c.isXRechnung))

            val b = c.buyer
            required("buyer.name", b.name)
            required("buyer.street", b.street)
            required("buyer.postalCode", b.postalCode)
            required("buyer.city", b.city)
            when {
                b.countryCode.isNullOrBlank() -> add(Problem("buyer.countryCode", "REQUIRED"))
                !COUNTRY.matches(b.countryCode) -> add(Problem("buyer.countryCode", "INVALID"))
            }
            if (!b.email.isNullOrBlank() && !EMAIL.matches(b.email.trim())) add(Problem("buyer.email", "INVALID"))
            if (c.isXRechnung) {
                // BR-DE-15 (buyer reference) and PEPPOL-EN16931-R010 (buyer electronic address).
                if (c.buyerReference.isNullOrBlank()) add(Problem("buyerReference", "REQUIRED_FOR_XRECHNUNG"))
                if (b.email.isNullOrBlank()) add(Problem("buyer.email", "REQUIRED_FOR_XRECHNUNG"))
            }

            // § 14 Abs. 4 Nr. 6 UStG: date of supply or service period.
            val period = c.periodStart != null || c.periodEnd != null
            if (c.deliveryDate == null && !period) add(Problem("deliveryDate", "REQUIRED"))
            if (period) {
                if (c.periodStart == null) add(Problem("periodStart", "REQUIRED"))
                if (c.periodEnd == null) add(Problem("periodEnd", "REQUIRED"))
                if (c.periodStart != null && c.periodEnd != null && c.periodEnd.isBefore(c.periodStart)) {
                    add(Problem("periodEnd", "PERIOD_ORDER"))
                }
            }
            if (c.issueDate != null && c.dueDate != null && c.dueDate.isBefore(c.issueDate)) add(Problem("dueDate", "BEFORE_ISSUE_DATE"))

            val treatment = c.vatTreatment ?: VatTreatment.DOMESTIC
            if (settings.kleinunternehmer && treatment != VatTreatment.DOMESTIC) add(Problem("vatTreatment", "NOT_FOR_KLEINUNTERNEHMER"))
            if (!settings.kleinunternehmer) {
                when (treatment) {
                    // BR-AE-02 / § 14a Abs. 5 UStG: the customer's VAT ID.
                    VatTreatment.REVERSE_CHARGE -> {
                        if (b.vatId.isNullOrBlank()) add(Problem("buyer.vatId", "VAT_ID_REQUIRED"))
                    }

                    // BR-IC-02 / § 14a Abs. 3 UStG: both VAT IDs, customer in another EU country.
                    VatTreatment.INTRA_EU -> {
                        if (b.vatId.isNullOrBlank()) add(Problem("buyer.vatId", "VAT_ID_REQUIRED"))
                        if (seller.vatId.isNullOrBlank()) add(Problem("seller.vatId", "VAT_ID_REQUIRED"))
                        if (b.countryCode == seller.countryCode) add(Problem("buyer.countryCode", "SAME_COUNTRY"))
                    }

                    VatTreatment.DOMESTIC -> {
                        Unit
                    }
                }
            }

            if (c.lines.isEmpty()) add(Problem("lines", "REQUIRED"))
            if (c.lines.size > MAX_LINES) add(Problem("lines", "TOO_MANY"))
            c.lines.forEachIndexed { i, line ->
                val p = "lines[$i]"
                required("$p.name", line.name)
                when {
                    line.quantity == null -> add(Problem("$p.quantity", "REQUIRED"))
                    line.quantity.signum() <= 0 -> add(Problem("$p.quantity", "INVALID"))
                }
                when {
                    line.unitCode.isNullOrBlank() -> add(Problem("$p.unitCode", "REQUIRED"))
                    !UNIT.matches(line.unitCode) -> add(Problem("$p.unitCode", "INVALID"))
                }
                when {
                    line.netPrice == null -> add(Problem("$p.netPrice", "REQUIRED"))
                    line.netPrice < BigDecimal.ZERO -> add(Problem("$p.netPrice", "INVALID"))
                }
                if (!settings.kleinunternehmer && treatment == VatTreatment.DOMESTIC && line.vatRate == null) {
                    add(Problem("$p.vatRate", "REQUIRED"))
                }
            }
            // A credit note or cancellation must credit something.
            if (kind != InvoiceKind.INVOICE && c.lines.isNotEmpty() && c.lines.all { (it.netPrice ?: BigDecimal.ONE).signum() == 0 }) {
                add(Problem("lines", "ZERO_AMOUNT"))
            }
        }
}
