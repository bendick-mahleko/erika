package de.erika.invoicecore.view

/**
 * German meanings for the codes a reader meets most often. Unknown codes are shown as they
 * are, so a missing entry never hides a value.
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
}
