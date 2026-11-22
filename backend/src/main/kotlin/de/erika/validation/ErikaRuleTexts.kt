package de.erika.validation

/**
 * Texts for the rule IDs ERIKA adds around the KoSIT rules: file format problems, files
 * that aren't e-invoices, XML Schema errors and PDF/A-3. KoSIT rule texts are in the
 * pack (`rule-texts.de.json`); content-check texts are in invoice-core (`RuleTexts`).
 * `RuleTextCatalogueTest` checks that every ID ERIKA can emit has a German text.
 */
internal object ErikaRuleTexts {
    private val texts: Map<String, Pair<String, String>> =
        mapOf(
            "ERIKA-FORMAT-TOO_LARGE" to
                Pair(
                    "Die Datei ist größer als 20 MB und wird nicht verarbeitet.",
                    "The file is larger than 20 MB and is not processed.",
                ),
            "ERIKA-FORMAT-MALFORMED_XML" to
                Pair(
                    "Die XML-Datei ist beschädigt oder unvollständig und kann nicht gelesen werden. Bitten Sie den " +
                        "Absender um eine neue Datei.",
                    "The XML file is damaged or incomplete and can't be read. Ask the sender for a new file.",
                ),
            "ERIKA-FORMAT-FORBIDDEN_XML_CONSTRUCT" to
                Pair(
                    "Die XML-Datei enthält eine Dokumenttyp-Definition (DTD). Aus Sicherheitsgründen werden solche " +
                        "Dateien nicht verarbeitet; E-Rechnungen benötigen keine DTD.",
                    "The XML file contains a document type definition (DTD). For security reasons such files are not " +
                        "processed; e-invoices don't need one.",
                ),
            "ERIKA-FORMAT-TOO_MANY_LINES" to
                Pair(
                    "Die Rechnung hat mehr als 5.000 Positionen und wird nicht verarbeitet.",
                    "The invoice has more than 5,000 lines and is not processed.",
                ),
            "ERIKA-FORMAT-MALFORMED_PDF" to
                Pair(
                    "Die PDF-Datei ist beschädigt oder mit einem Passwort geschützt und kann nicht gelesen werden.",
                    "The PDF is damaged or password-protected and can't be read.",
                ),
            "ERIKA-FORMAT-UNKNOWN_FILE_TYPE" to
                Pair(
                    "Die Datei ist weder XML noch PDF und daher keine E-Rechnung.",
                    "The file is neither XML nor PDF and therefore not an e-invoice.",
                ),
            "ERIKA-NOT-EINVOICE-PLAIN_PDF" to
                Pair(
                    "Diese PDF-Datei enthält keine strukturierten Rechnungsdaten. Sie ist eine „sonstige Rechnung“, " +
                        "keine E-Rechnung im Sinne von § 14 UStG.",
                    "This PDF contains no structured invoice data. It is an 'other invoice', not an e-invoice under §" +
                        " 14 UStG.",
                ),
            "ERIKA-NOT-EINVOICE-INSUFFICIENT_PROFILE" to
                Pair(
                    "Die Datei nutzt das ZUGFeRD-Profil MINIMUM oder BASIC-WL. Diese Profile enthalten nicht alle " +
                        "Pflichtangaben und gelten nicht als E-Rechnung.",
                    "The file uses the ZUGFeRD profile MINIMUM or BASIC-WL. These profiles lack mandatory content and" +
                        " don't count as e-invoices.",
                ),
            "ERIKA-NOT-EINVOICE-ZUGFERD_1" to
                Pair(
                    "Die Datei ist eine ZUGFeRD-1.0-Rechnung. Dieses veraltete Format entspricht nicht der EN 16931; " +
                        "bitten Sie den Absender um ZUGFeRD 2 oder XRechnung.",
                    "The file is a ZUGFeRD 1.0 invoice. This outdated format doesn't conform to EN 16931; ask the " +
                        "sender for ZUGFeRD 2 or XRechnung.",
                ),
            "ERIKA-NOT-EINVOICE-UNKNOWN_XML" to
                Pair(
                    "Die XML-Datei ist keine Rechnung im Format UBL oder CII.",
                    "The XML file is not a UBL or CII invoice.",
                ),
            "ERIKA-NOT-EINVOICE-UNKNOWN_PROFILE" to
                Pair(
                    "Die Rechnung nennt eine unbekannte Spezifikation (BT-24). Sie kann nicht gegen die passenden " +
                        "Regeln geprüft werden.",
                    "The invoice names an unknown specification (BT-24). It can't be checked against matching rules.",
                ),
            "ERIKA-NO-SCENARIO" to
                Pair(
                    "Für die angegebene Spezifikation (BT-24) gibt es keine Prüfregeln. Die Datei ist keine gültige " +
                        "XRechnung oder EN-16931-Rechnung.",
                    "There are no validation rules for the given specification (BT-24). The file is not a valid " +
                        "XRechnung or EN 16931 invoice.",
                ),
            "ERIKA-PROCESSING-ERROR" to
                Pair(
                    "Die Prüfung konnte nicht vollständig durchgeführt werden. Die Datei sollte nicht automatisiert " +
                        "angenommen werden.",
                    "The validation couldn't be completed. The file should not be accepted automatically.",
                ),
            "XSD" to
                Pair(
                    "Die Datei entspricht nicht dem vorgeschriebenen XML-Schema: ein Element fehlt, steht an der " +
                        "falschen Stelle oder hat einen unzulässigen Wert. Die technische Meldung nennt die Stelle.",
                    "The file doesn't match the required XML Schema: an element is missing, in the wrong place, or " +
                        "has an invalid value.",
                ),
            "XML" to
                Pair(
                    "Die Datei ist kein gültiges XML-Dokument.",
                    "The file is not a well-formed XML document.",
                ),
            "PDFA-3" to
                Pair(
                    "Die PDF-Datei erfüllt die Anforderung {clause} des Archivformats PDF/A-3 nicht. ZUGFeRD-" +
                        "Rechnungen müssen PDF/A-3 sein.",
                    "The PDF doesn't meet requirement {clause} of PDF/A-3. ZUGFeRD invoices must be PDF/A-3.",
                ),
            "PDFA-UNREADABLE" to
                Pair(
                    "Der PDF-Teil der ZUGFeRD-Rechnung konnte nicht auf PDF/A-3 geprüft werden, weil er nicht lesbar " +
                        "ist.",
                    "The PDF part of the ZUGFeRD invoice couldn't be checked for PDF/A-3 because it can't be read.",
                ),
        )

    val de: Map<String, String> = texts.mapValues { it.value.first }
    val en: Map<String, String> = texts.mapValues { it.value.second }
}
