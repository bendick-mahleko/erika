package de.erika.export

import de.erika.documents.ExportDocument
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Chart of accounts (Standardkontenrahmen). */
enum class DatevChart(
    val code: String,
    val expense: String,
    val revenue: String,
) {
    /** SKR 03: 4900 Sonstige betriebliche Aufwendungen, 8200 Erlöse (both without automatic VAT). */
    SKR03("03", "4900", "8200"),

    /** SKR 04: 6300 Sonstige betriebliche Aufwendungen, 4200 Erlöse (both without automatic VAT). */
    SKR04("04", "6300", "4200"),
}

/** The workspace's DATEV settings with defaults filled in. */
data class DatevConfig(
    val consultantNumber: Int?,
    val clientNumber: Int?,
    val chart: DatevChart = DatevChart.SKR03,
    val fiscalYearStartMonth: Int = 1,
    val accountLength: Int = 4,
    val expenseAccount: String? = null,
    val revenueAccount: String? = null,
    val creditorAccount: String? = null,
    val debtorAccount: String? = null,
) {
    /** Without consultant and client number DATEV refuses the file. */
    val complete get() = consultantNumber != null && clientNumber != null

    /** General ledger accounts are right-padded with zeros to the account length (4900 → 49000). */
    val expense get() = expenseAccount ?: chart.expense.padEnd(accountLength, '0')
    val revenue get() = revenueAccount ?: chart.revenue.padEnd(accountLength, '0')

    /** Personal accounts have one digit more: debtors from 10000, creditors from 70000 (length 4). */
    val debtor get() = debtorAccount ?: "1".padEnd(accountLength + 1, '0')
    val creditor get() = creditorAccount ?: "7".padEnd(accountLength + 1, '0')
}

/** One line of the Buchungsstapel. [amount] is always positive; [debitCredit] says which side [account] is on. */
data class DatevBooking(
    val amount: BigDecimal,
    val debitCredit: String,
    val currency: String,
    val account: String,
    val contraAccount: String,
    val taxKey: String?,
    val date: LocalDate,
    val documentNumber: String,
    val text: String,
    val euVatId: String?,
    val documentFile: String?,
    val guid: UUID,
)

/**
 * DATEV-Format "Buchungsstapel" (EXTF, format category 21, version 13) for the tax advisor's
 * import (EX-01). One booking per invoice and VAT rate, against a personal account (creditor
 * for received, debtor for issued invoices) and a general ledger account without automatic VAT,
 * with the tax key for 19 % and 7 %. Other VAT cases (reverse charge, intra-EU, exempt) get no
 * tax key and are left to the advisor; the batch is not committed (Festschreibung 0), so the
 * advisor can still change every line.
 *
 * Format: semicolon-separated, Windows-1252, CRLF, decimal comma, text in double quotes.
 */
object DatevCsv {
    val CHARSET: Charset = Charset.forName("windows-1252")

    /** The fiscal years that [from]…[to] touches, each clipped to the period: (fiscal year start, from, to). */
    fun fiscalYears(
        from: LocalDate,
        to: LocalDate,
        startMonth: Int,
    ): List<Triple<LocalDate, LocalDate, LocalDate>> {
        val result = mutableListOf<Triple<LocalDate, LocalDate, LocalDate>>()
        var start = fiscalYearStart(from, startMonth)
        while (!start.isAfter(to)) {
            val end = start.plusYears(1).minusDays(1)
            result += Triple(start, maxOf(from, start), minOf(to, end))
            start = start.plusYears(1)
        }
        return result
    }

    fun fiscalYearStart(
        date: LocalDate,
        startMonth: Int,
    ): LocalDate {
        val candidate = LocalDate.of(date.year, startMonth, 1)
        return if (candidate.isAfter(date)) candidate.minusYears(1) else candidate
    }

    /** The bookings for the documents that carry amounts; [fileOf] gives each document's path in the ZIP. */
    fun bookings(
        documents: List<ExportDocument>,
        config: DatevConfig,
        fileOf: (ExportDocument) -> String?,
    ): List<DatevBooking> =
        documents.flatMap { doc ->
            if (doc.gross == null) return@flatMap emptyList()
            val incoming = doc.direction == "IN"
            val creditNote = doc.typeCode == "381"
            val parts =
                doc.vatLines
                    .mapNotNull { line ->
                        val amount = (line.taxableAmount ?: BigDecimal.ZERO) + (line.taxAmount ?: BigDecimal.ZERO)
                        if (line.taxableAmount == null &&
                            line.taxAmount == null
                        ) {
                            null
                        } else {
                            amount to taxKey(line.categoryCode, line.rate, incoming)
                        }
                    }.ifEmpty { listOf(doc.gross to null) }
            parts.mapIndexedNotNull { i, (raw, key) ->
                val amount = raw.setScale(2, RoundingMode.HALF_UP)
                if (amount.signum() == 0) return@mapIndexedNotNull null
                // Received invoice: the creditor is credited (H); issued invoice: the debtor is debited (S).
                // A credit note or a negative amount turns it round.
                val base = if (incoming) "H" else "S"
                val flip = creditNote xor (amount.signum() < 0)
                val side = if (flip) (if (base == "H") "S" else "H") else base
                DatevBooking(
                    amount = amount.abs(),
                    debitCredit = side,
                    currency = doc.currency ?: "EUR",
                    account = if (incoming) config.creditor else config.debtor,
                    contraAccount = if (incoming) config.expense else config.revenue,
                    taxKey = key,
                    date = doc.bookingDate,
                    documentNumber = documentField(doc.invoiceNumber),
                    text = (if (incoming) doc.sellerName else doc.buyerName).orEmpty().take(TEXT_MAX),
                    euVatId =
                        doc.buyerVatId.takeIf {
                            !incoming && doc.vatLines.any { l -> l.categoryCode == "K" }
                        },
                    documentFile = fileOf(doc),
                    guid = UUID.nameUUIDFromBytes("${doc.id}/$i".toByteArray()),
                )
            }
        }

    /** DATEV tax keys: 9 / 8 input VAT 19 % / 7 %, 3 / 2 output VAT 19 % / 7 %. */
    fun taxKey(
        category: String?,
        rate: BigDecimal?,
        incoming: Boolean,
    ): String? {
        if (category != "S" || rate == null) return null
        return when (rate.stripTrailingZeros().toPlainString()) {
            "19" -> if (incoming) "9" else "3"
            "7" -> if (incoming) "8" else "2"
            else -> null
        }
    }

    /** Belegfeld 1: at most 36 characters of A–Z, a–z, 0–9 and `$ & % * + - . /`. */
    fun documentField(number: String?): String = number.orEmpty().filter { it in DOCUMENT_CHARS }.take(DOCUMENT_MAX)

    /** The whole file: header line, column line, one line per booking. */
    fun write(
        config: DatevConfig,
        fiscalYearStart: LocalDate,
        from: LocalDate,
        to: LocalDate,
        bookings: List<DatevBooking>,
        created: LocalDateTime,
        label: String,
    ): ByteArray {
        val out = StringBuilder()
        val header =
            listOf(
                text("EXTF"),
                "700",
                "21",
                text("Buchungsstapel"),
                "13",
                created.format(TIMESTAMP),
                "",
                text("RE"),
                text("ERIKA"),
                text(""),
                config.consultantNumber?.toString().orEmpty(),
                config.clientNumber?.toString().orEmpty(),
                fiscalYearStart.format(DATE),
                config.accountLength.toString(),
                from.format(DATE),
                to.format(DATE),
                text(label.take(LABEL_MAX)),
                text(""),
                "1",
                "0",
                "0",
                text("EUR"),
                "",
                text(""),
                "",
                "",
                text(config.chart.code),
                "",
                "",
                text(""),
                text(""),
            )
        out.append(header.joinToString(";")).append(CRLF)
        out.append(COLUMNS.joinToString(";")).append(CRLF)
        bookings.forEach { b ->
            val fields = arrayOfNulls<String>(COLUMNS.size)
            fields[0] =
                b.amount
                    .setScale(2, RoundingMode.HALF_UP)
                    .toPlainString()
                    .replace('.', ',')
            fields[1] = text(b.debitCredit)
            fields[2] = text(b.currency)
            fields[6] = b.account
            fields[7] = b.contraAccount
            fields[8] = b.taxKey?.let(::text)
            fields[9] = b.date.format(DAY_MONTH)
            fields[10] = text(b.documentNumber)
            fields[13] = text(b.text)
            b.documentFile?.let {
                fields[20] = text("Datei im Export")
                fields[21] = text(it.takeLast(INFO_MAX))
            }
            fields[39] = b.euVatId?.let { text(it.replace(" ", "")) }
            fields[102] = text(b.guid.toString())
            fields[113] = "0"
            out.append(fields.joinToString(";") { it.orEmpty() }).append(CRLF)
        }
        return out.toString().toByteArray(CHARSET)
    }

    private fun text(value: String) = "\"" + clean(value).replace("\"", "\"\"") + "\""

    /** Characters Windows-1252 can't encode become '?' anyway; line breaks would break the record. */
    private fun clean(value: String) = value.replace('\r', ' ').replace('\n', ' ').replace(';', ',')

    private const val CRLF = "\r\n"
    private const val TEXT_MAX = 60
    private const val DOCUMENT_MAX = 36
    private const val LABEL_MAX = 30
    private const val INFO_MAX = 210
    private val DOCUMENT_CHARS = (('A'..'Z') + ('a'..'z') + ('0'..'9') + "$&%*+-./".toList()).toSet()
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DAY_MONTH = DateTimeFormatter.ofPattern("ddMM")

    /** Column names of format version 13 (125 fields), in order. */
    val COLUMNS: List<String> =
        buildList {
            addAll(
                listOf(
                    "Umsatz (ohne Soll/Haben-Kz)",
                    "Soll/Haben-Kennzeichen",
                    "WKZ Umsatz",
                    "Kurs",
                    "Basis-Umsatz",
                    "WKZ Basis-Umsatz",
                    "Konto",
                    "Gegenkonto (ohne BU-Schlüssel)",
                    "BU-Schlüssel",
                    "Belegdatum",
                    "Belegfeld 1",
                    "Belegfeld 2",
                    "Skonto",
                    "Buchungstext",
                    "Postensperre",
                    "Diverse Adressnummer",
                    "Geschäftspartnerbank",
                    "Sachverhalt",
                    "Zinssperre",
                    "Beleglink",
                ),
            )
            (1..8).forEach {
                add("Beleginfo - Art $it")
                add("Beleginfo - Inhalt $it")
            }
            addAll(
                listOf(
                    "KOST1 - Kostenstelle",
                    "KOST2 - Kostenstelle",
                    "Kost-Menge",
                    "EU-Land u. UStID (Bestimmung)",
                    "EU-Steuersatz (Bestimmung)",
                    "Abw. Versteuerungsart",
                    "Sachverhalt L+L",
                    "Funktionsergänzung L+L",
                    "BU 49 Hauptfunktionstyp",
                    "BU 49 Hauptfunktionsnummer",
                    "BU 49 Funktionsergänzung",
                ),
            )
            (1..20).forEach {
                add("Zusatzinformation - Art $it")
                add("Zusatzinformation- Inhalt $it")
            }
            addAll(
                listOf(
                    "Stück",
                    "Gewicht",
                    "Zahlweise",
                    "Forderungsart",
                    "Veranlagungsjahr",
                    "Zugeordnete Fälligkeit",
                    "Skontotyp",
                    "Auftragsnummer",
                    "Buchungstyp",
                    "USt-Schlüssel (Anzahlungen)",
                    "EU-Mitgliedstaat (Anzahlungen)",
                    "Sachverhalt L+L (Anzahlungen)",
                    "EU-Steuersatz (Anzahlungen)",
                    "Erlöskonto (Anzahlungen)",
                    "Herkunft-Kz",
                    "Buchungs GUID",
                    "KOST-Datum",
                    "SEPA-Mandatsreferenz",
                    "Skontosperre",
                    "Gesellschaftername",
                    "Beteiligtennummer",
                    "Identifikationsnummer",
                    "Zeichnernummer",
                    "Postensperre bis",
                    "Bezeichnung SoBil-Sachverhalt",
                    "Kennzeichen SoBil-Buchung",
                    "Festschreibung",
                    "Leistungsdatum",
                    "Datum Zuord. Steuerperiode",
                    "Fälligkeit",
                    "Generalumkehr (GU)",
                    "Steuersatz",
                    "Land",
                    "Abrechnungsreferenz",
                    "BVV-Position",
                    "EU-Land u. UStID (Ursprung)",
                    "EU-Steuersatz (Ursprung)",
                    "Abw. Skontokonto",
                ),
            )
        }
}
