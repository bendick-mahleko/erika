package de.erika.invoicecore.write

import java.math.BigDecimal

/**
 * The VAT cases an invoice creator offers (ERIKA CR-04, BR-06), each with its EN 16931
 * category code (UNCL 5305), rate, exemption reason (BT-120/121) and the note (BT-22) the
 * invoice must carry under the UStG.
 *
 * The note texts are the common wording; the customer remains responsible for choosing the
 * right case.
 */
public enum class VatCase(
    public val categoryCode: String,
    public val rate: BigDecimal,
    /** VATEX code for BT-121, where one exists. */
    public val exemptionReasonCode: String?,
    /** BT-120; required by EN 16931 for E, AE and K. */
    public val exemptionReason: String?,
    /** Text for the invoice note (BT-22) and the printed invoice, or null if none is needed. */
    public val note: String?,
) {
    /** Standard rate 19 % (§ 12 Abs. 1 UStG). */
    STANDARD_19("S", BigDecimal("19"), null, null, null),

    /** Reduced rate 7 % (§ 12 Abs. 2 UStG). */
    REDUCED_7("S", BigDecimal("7"), null, null, null),

    /** Zero rate (§ 12 Abs. 3 UStG, e.g. solar installations). */
    ZERO("Z", BigDecimal("0"), null, null, null),

    /** Reverse charge: the customer owes the VAT (§ 13b UStG). Needs the customer's VAT ID. */
    REVERSE_CHARGE(
        "AE",
        BigDecimal("0"),
        "VATEX-EU-AE",
        "Steuerschuldnerschaft des Leistungsempfängers",
        "Steuerschuldnerschaft des Leistungsempfängers (§ 13b UStG).",
    ),

    /** Tax-free intra-EU supply (§ 4 Nr. 1 Buchst. b, § 6a UStG). Needs both VAT IDs. */
    INTRA_EU_SUPPLY(
        "K",
        BigDecimal("0"),
        "VATEX-EU-IC",
        "Steuerfreie innergemeinschaftliche Lieferung",
        "Steuerfreie innergemeinschaftliche Lieferung nach § 4 Nr. 1 Buchst. b i. V. m. § 6a UStG.",
    ),

    /** Kleinunternehmer: no VAT charged (§ 19 UStG, BR-06). */
    KLEINUNTERNEHMER(
        "E",
        BigDecimal("0"),
        null,
        "Kleinunternehmer gemäß § 19 UStG",
        "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet (Kleinunternehmerregelung).",
    ),
    ;

    public companion object {
        /** The case for a category and rate as found on a line, or null if ERIKA doesn't create it. */
        public fun of(
            categoryCode: String?,
            rate: BigDecimal?,
        ): VatCase? = entries.firstOrNull { it.categoryCode == categoryCode && (rate == null || it.rate.compareTo(rate) == 0) }
    }
}
