package de.erika.invoicecore.check

import de.erika.invoicecore.check.ArithmeticChecks as A
import de.erika.invoicecore.check.MandatoryContentChecks as U

/**
 * Plain-language texts for the content-check rules (VW-04), German first.
 * Placeholders: {expected}, {actual}, {category}; the line ID is appended.
 */
public object RuleTexts {
    private data class Text(
        val de: String,
        val en: String,
    )

    private val texts: Map<String, Text> =
        mapOf(
            A.LINE_NET_TOTAL to
                Text(
                    "Die Summe der Positionsbeträge ({actual}) stimmt nicht mit der Summe aller Positionen ({expected}) überein.",
                    "The sum of line net amounts ({actual}) doesn't match the total of all lines ({expected}).",
                ),
            A.ALLOWANCE_TOTAL to
                Text(
                    "Die Summe der Nachlässe ({actual}) stimmt nicht mit den einzelnen Nachlässen ({expected}) überein.",
                    "The sum of allowances ({actual}) doesn't match the individual allowances ({expected}).",
                ),
            A.CHARGE_TOTAL to
                Text(
                    "Die Summe der Zuschläge ({actual}) stimmt nicht mit den einzelnen Zuschlägen ({expected}) überein.",
                    "The sum of charges ({actual}) doesn't match the individual charges ({expected}).",
                ),
            A.TOTAL_WITHOUT_VAT to
                Text(
                    "Der Gesamtbetrag ohne Umsatzsteuer ({actual}) ist falsch berechnet; richtig wäre {expected}.",
                    "The total without VAT ({actual}) is calculated incorrectly; it should be {expected}.",
                ),
            A.TOTAL_VAT to
                Text(
                    "Die gesamte Umsatzsteuer ({actual}) entspricht nicht der Summe der Steuerbeträge je Steuersatz ({expected}).",
                    "The total VAT ({actual}) doesn't match the sum of VAT per rate ({expected}).",
                ),
            A.TOTAL_WITH_VAT to
                Text(
                    "Der Bruttobetrag ({actual}) ist nicht Nettobetrag plus Umsatzsteuer ({expected}).",
                    "The total with VAT ({actual}) isn't the net total plus VAT ({expected}).",
                ),
            A.AMOUNT_DUE to
                Text(
                    "Der Zahlbetrag ({actual}) ist falsch berechnet; richtig wäre {expected} (Bruttobetrag minus Anzahlungen plus Rundung).",
                    "The amount due ({actual}) is wrong; it should be {expected} (total with VAT minus prepaid amount plus rounding).",
                ),
            A.VAT_AMOUNT to
                Text(
                    "Der Steuerbetrag für {category} ({actual}) passt nicht zur Bemessungsgrundlage; erwartet {expected}.",
                    "The VAT amount for {category} ({actual}) doesn't match the taxable amount; expected {expected}.",
                ),
            A.TAXABLE_AMOUNT to
                Text(
                    "Die Bemessungsgrundlage für {category} ({actual}) entspricht nicht den Positionen, Nachlässen und Zuschlägen dieses Steuersatzes ({expected}).",
                    "The taxable amount for {category} ({actual}) doesn't match the lines, allowances and charges with this rate ({expected}).",
                ),
            A.LINE_NET_AMOUNT to
                Text(
                    "Der Positionsbetrag ({actual}) entspricht nicht Menge mal Preis ({expected}).",
                    "The line net amount ({actual}) isn't quantity times price ({expected}).",
                ),
            A.NET_PRICE to
                Text(
                    "Der Nettopreis ({actual}) ist nicht Bruttopreis minus Rabatt ({expected}).",
                    "The net price ({actual}) isn't the gross price minus the discount ({expected}).",
                ),
            A.MISSING_VAT_BREAKDOWN to
                Text(
                    "Für die Steuerkategorie {category} fehlt die Aufschlüsselung der Umsatzsteuer.",
                    "The VAT breakdown for category {category} is missing.",
                ),
            A.ALLOWANCE_PERCENTAGE to
                Text(
                    "Der Nachlass oder Zuschlag ({actual}) entspricht nicht Prozentsatz mal Basisbetrag ({expected}).",
                    "The allowance or charge ({actual}) isn't the percentage of the base amount ({expected}).",
                ),
            U.INVOICE_NUMBER to Text("Die Rechnungsnummer fehlt (§ 14 Abs. 4 Nr. 4 UStG).", "The invoice number is missing."),
            U.ISSUE_DATE to Text("Das Rechnungsdatum fehlt (§ 14 Abs. 4 Nr. 3 UStG).", "The invoice date is missing."),
            U.SELLER_NAME to Text("Der Name des Verkäufers fehlt (§ 14 Abs. 4 Nr. 1 UStG).", "The seller's name is missing."),
            U.SELLER_ADDRESS to
                Text("Die vollständige Anschrift des Verkäufers fehlt (§ 14 Abs. 4 Nr. 1 UStG).", "The seller's full address is missing."),
            U.BUYER_NAME_ADDRESS to
                Text(
                    "Name oder vollständige Anschrift des Käufers fehlen (§ 14 Abs. 4 Nr. 1 UStG).",
                    "The buyer's name or full address is missing.",
                ),
            U.SELLER_TAX_ID to
                Text(
                    "Weder Steuernummer noch Umsatzsteuer-Identifikationsnummer des Verkäufers sind angegeben (§ 14 Abs. 4 Nr. 2 UStG).",
                    "Neither the seller's tax number nor VAT ID is given.",
                ),
            U.DELIVERY_DATE to
                Text(
                    "Liefer- oder Leistungsdatum fehlen (§ 14 Abs. 4 Nr. 6 UStG). Prüfen Sie, ob es im Rechnungstext steht.",
                    "The delivery date or period is missing. Check whether it's stated in the invoice text.",
                ),
            U.LINE_DESCRIPTION to
                Text(
                    "Die Bezeichnung der Leistung fehlt (§ 14 Abs. 4 Nr. 5 UStG).",
                    "The description of the goods or services is missing.",
                ),
            U.LINE_QUANTITY to Text("Die Menge fehlt (§ 14 Abs. 4 Nr. 5 UStG).", "The quantity is missing."),
            U.VAT_BREAKDOWN to
                Text(
                    "Die Aufschlüsselung nach Steuersätzen mit Nettobetrag und Steuerbetrag fehlt (§ 14 Abs. 4 Nr. 7 und 8 UStG).",
                    "The breakdown by VAT rate with net and tax amounts is missing.",
                ),
            U.EXEMPTION_REASON to
                Text(
                    "Für die Steuerkategorie {category} fehlt der Hinweis auf die Steuerbefreiung bzw. Steuerschuldnerschaft (§ 14 Abs. 4 Nr. 8 UStG).",
                    "For VAT category {category}, the note on the exemption or reverse charge is missing.",
                ),
            U.REVERSE_CHARGE_BUYER_VAT_ID to
                Text(
                    "Bei Steuerschuldnerschaft des Leistungsempfängers fehlt die USt-IdNr. des Käufers (§ 14a Abs. 1 UStG).",
                    "For reverse charge, the buyer's VAT ID is missing.",
                ),
            U.INTRA_EU_VAT_IDS to
                Text(
                    "Bei einer innergemeinschaftlichen Lieferung fehlen die USt-IdNr. von Verkäufer oder Käufer (§ 14a Abs. 3 UStG).",
                    "For an intra-EU supply, the seller's or buyer's VAT ID is missing.",
                ),
            U.SELF_BILLING_NOTE to
                Text(
                    "Eine Gutschrift im Sinne des § 14 Abs. 2 UStG muss die Angabe „Gutschrift“ enthalten.",
                    "A self-billed invoice must contain the word \"Gutschrift\".",
                ),
        )

    /** All rule IDs that have texts. */
    public val ruleIds: Set<String> get() = texts.keys

    public fun render(
        finding: Finding,
        language: String = "de",
    ): String {
        val text = texts[finding.ruleId] ?: return finding.ruleId
        var result = if (language == "en") text.en else text.de
        finding.params.forEach { (key, value) -> result = result.replace("{$key}", value) }
        if (finding.lineId != null) result += if (language == "en") " (line ${finding.lineId})" else " (Position ${finding.lineId})"
        return result
    }
}
