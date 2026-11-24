package de.erika.invoicecore.view

/** Language of the invoice view (VW-07). German is the default and the language of the PDF view copy. */
public enum class ViewLanguage { DE, EN }

/**
 * German meanings for the codes a reader meets most often, with English counterparts in
 * [CodeListsEn]. Unknown codes are shown as they are, so a missing entry never hides a value.
 */
internal object CodeLists {
    /** UNTDID 1001 (BT-3). */
    val invoiceType =
        mapOf(
            "326" to "Teilrechnung",
            "380" to "Rechnung",
            "381" to "Gutschrift",
            "383" to "Belastungsanzeige",
            "384" to "Rechnungskorrektur",
            "386" to "Vorauszahlungsrechnung",
            "389" to "Selbst ausgestellte Rechnung (Gutschriftsverfahren)",
            "751" to "Rechnungsinformation für Buchungszwecke",
            "875" to "Abschlagsrechnung (Bauleistung)",
            "876" to "Teilschlussrechnung (Bauleistung)",
            "877" to "Schlussrechnung (Bauleistung)",
        )

    /** UNCL 5305 (BT-95, BT-102, BT-118, BT-151). */
    val vatCategory =
        mapOf(
            "S" to "Umsatzsteuer zum Normal- oder ermäßigten Satz",
            "Z" to "Nullsatz",
            "E" to "Steuerbefreit",
            "AE" to "Steuerschuldnerschaft des Leistungsempfängers (Reverse Charge)",
            "K" to "Innergemeinschaftliche Lieferung",
            "G" to "Ausfuhr außerhalb der EU",
            "O" to "Nicht steuerbar",
            "L" to "Kanarische Inseln (IGIC)",
            "M" to "Ceuta und Melilla (IPSI)",
        )

    /** UNCL 4461 (BT-81). */
    val paymentMeans =
        mapOf(
            "1" to "Nicht festgelegt",
            "10" to "Barzahlung",
            "20" to "Scheck",
            "30" to "Überweisung",
            "31" to "Überweisung",
            "42" to "Zahlung auf ein Bankkonto",
            "48" to "Zahlungskarte",
            "49" to "Lastschrift",
            "54" to "Kreditkarte",
            "55" to "Debitkarte",
            "57" to "Dauerauftrag",
            "58" to "SEPA-Überweisung",
            "59" to "SEPA-Lastschrift",
            "97" to "Verrechnung",
            "ZZZ" to "Gesondert vereinbart",
        )

    /** UN/ECE Recommendation 20/21 (BT-130, BT-150), the units used most on German invoices. */
    val unit =
        mapOf(
            "C62" to "Stück",
            "H87" to "Stück",
            "EA" to "Stück",
            "XPP" to "Stück",
            "PR" to "Paar",
            "SET" to "Satz",
            "LS" to "Pauschale",
            "HUR" to "Stunde",
            "MIN" to "Minute",
            "DAY" to "Tag",
            "WEE" to "Woche",
            "MON" to "Monat",
            "ANN" to "Jahr",
            "KGM" to "Kilogramm",
            "GRM" to "Gramm",
            "TNE" to "Tonne",
            "MTR" to "Meter",
            "KMT" to "Kilometer",
            "MTK" to "Quadratmeter",
            "MTQ" to "Kubikmeter",
            "LTR" to "Liter",
            "KWH" to "Kilowattstunde",
            "P1" to "Prozent",
        )

    /** UNTDID 2005 (BT-8). */
    val vatPointDate =
        mapOf(
            "3" to "Rechnungsdatum",
            "35" to "Liefertermin",
            "432" to "Zahlungstermin",
        )

    fun describe(
        code: String,
        list: Map<String, String>,
    ): String = list[code.trim()]?.let { "$code – $it" } ?: code

    /** The list in [language]: English lists fall back to the German entry for codes they lack. */
    fun of(
        list: Map<String, String>,
        language: ViewLanguage,
    ): Map<String, String> =
        if (language == ViewLanguage.DE) {
            list
        } else {
            val en =
                when (list) {
                    invoiceType -> CodeListsEn.invoiceType
                    vatCategory -> CodeListsEn.vatCategory
                    paymentMeans -> CodeListsEn.paymentMeans
                    unit -> CodeListsEn.unit
                    vatPointDate -> CodeListsEn.vatPointDate
                    else -> emptyMap()
                }
            list + en
        }
}

internal object CodeListsEn {
    val invoiceType =
        mapOf(
            "326" to "Partial invoice",
            "380" to "Commercial invoice",
            "381" to "Credit note",
            "383" to "Debit note",
            "384" to "Corrected invoice",
            "386" to "Prepayment invoice",
            "389" to "Self-billed invoice",
            "751" to "Invoice information for accounting purposes",
            "875" to "Partial construction invoice",
            "876" to "Partial final construction invoice",
            "877" to "Final construction invoice",
        )
    val vatCategory =
        mapOf(
            "S" to "Standard or reduced rate",
            "Z" to "Zero rated",
            "E" to "Exempt from VAT",
            "AE" to "VAT reverse charge",
            "K" to "Intra-community supply",
            "G" to "Export outside the EU",
            "O" to "Not subject to VAT",
            "L" to "Canary Islands (IGIC)",
            "M" to "Ceuta and Melilla (IPSI)",
        )
    val paymentMeans =
        mapOf(
            "1" to "Not defined",
            "10" to "Cash",
            "20" to "Cheque",
            "30" to "Credit transfer",
            "31" to "Debit transfer",
            "42" to "Payment to bank account",
            "48" to "Bank card",
            "49" to "Direct debit",
            "54" to "Credit card",
            "55" to "Debit card",
            "57" to "Standing agreement",
            "58" to "SEPA credit transfer",
            "59" to "SEPA direct debit",
            "97" to "Clearing between partners",
            "ZZZ" to "Mutually defined",
        )
    val unit =
        mapOf(
            "C62" to "piece",
            "H87" to "piece",
            "EA" to "each",
            "XPP" to "piece",
            "PR" to "pair",
            "SET" to "set",
            "LS" to "lump sum",
            "HUR" to "hour",
            "MIN" to "minute",
            "DAY" to "day",
            "WEE" to "week",
            "MON" to "month",
            "ANN" to "year",
            "KGM" to "kilogram",
            "GRM" to "gram",
            "TNE" to "tonne",
            "MTR" to "metre",
            "KMT" to "kilometre",
            "MTK" to "square metre",
            "MTQ" to "cubic metre",
            "LTR" to "litre",
            "KWH" to "kilowatt hour",
            "P1" to "percent",
        )
    val vatPointDate =
        mapOf(
            "3" to "Invoice issue date",
            "35" to "Delivery date",
            "432" to "Payment date",
        )
}
