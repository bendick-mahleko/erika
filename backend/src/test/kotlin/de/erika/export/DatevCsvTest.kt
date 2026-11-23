package de.erika.export

import de.erika.documents.ExportDocument
import de.erika.documents.ExportVatLine
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatevCsvTest {
    private val config = DatevConfig(consultantNumber = 1234567, clientNumber = 10001)

    private fun doc(
        direction: String = "IN",
        typeCode: String? = "380",
        gross: String? = "119.00",
        lines: List<ExportVatLine> = listOf(ExportVatLine("S", BigDecimal("19"), BigDecimal("100.00"), BigDecimal("19.00"))),
        number: String? = "R-2026/001",
    ) = ExportDocument(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        direction = direction,
        sha256 = "a".repeat(64),
        fileName = "r.xml",
        mediaType = "application/xml",
        receivedAt = OffsetDateTime.parse("2026-03-05T10:00:00+01:00"),
        format = "XRECHNUNG_CII",
        status = "NEW",
        validationLevel = "VALID",
        invoiceNumber = number,
        issueDate = LocalDate.of(2026, 3, 4),
        dueDate = null,
        typeCode = typeCode,
        currency = "EUR",
        sellerName = "Lieferant GmbH",
        sellerVatId = "DE111111111",
        buyerName = "Kunde \"AG\"",
        buyerVatId = "FR12345678901",
        net = BigDecimal("100.00"),
        tax = BigDecimal("19.00"),
        gross = gross?.let(::BigDecimal),
        vatLines = lines,
    )

    @Test
    fun `defaults follow the chart and the account length`() {
        assertEquals(listOf("4900", "8200", "70000", "10000"), listOf(config.expense, config.revenue, config.creditor, config.debtor))
        val skr04 = config.copy(chart = DatevChart.SKR04, accountLength = 5)
        assertEquals(listOf("63000", "42000", "700000", "100000"), listOf(skr04.expense, skr04.revenue, skr04.creditor, skr04.debtor))
        assertEquals("1600", config.copy(expenseAccount = "1600").expense)
        assertTrue(config.complete)
        assertTrue(!DatevConfig(null, 1).complete)
    }

    @Test
    fun `received and issued invoices, credit notes and negative amounts land on the right side`() {
        val received = DatevCsv.bookings(listOf(doc()), config) { "Belege/Eingang/r.xml" }.single()
        assertEquals(
            listOf("H", "70000", "4900", "9"),
            listOf(received.debitCredit, received.account, received.contraAccount, received.taxKey),
        )
        assertEquals(BigDecimal("119.00"), received.amount)
        assertEquals("Lieferant GmbH", received.text)

        val issued = DatevCsv.bookings(listOf(doc(direction = "OUT")), config) { null }.single()
        assertEquals(listOf("S", "10000", "8200", "3"), listOf(issued.debitCredit, issued.account, issued.contraAccount, issued.taxKey))
        assertEquals("S", DatevCsv.bookings(listOf(doc(typeCode = "381")), config) { null }.single().debitCredit)
        val negative = listOf(ExportVatLine("S", BigDecimal("7"), BigDecimal("-10.00"), BigDecimal("-0.70")))
        val refund = DatevCsv.bookings(listOf(doc(direction = "OUT", lines = negative)), config) { null }.single()
        assertEquals("H" to BigDecimal("10.70"), refund.debitCredit to refund.amount)
        assertEquals("2", refund.taxKey)
    }

    @Test
    fun `one booking per VAT rate, other cases without tax key, documents without amounts skipped`() {
        val mixed =
            listOf(
                ExportVatLine("S", BigDecimal("19.00"), BigDecimal("100.00"), BigDecimal("19.00")),
                ExportVatLine("S", BigDecimal("7"), BigDecimal("10.00"), BigDecimal("0.70")),
                ExportVatLine("K", BigDecimal("0"), BigDecimal("50.00"), BigDecimal("0.00")),
            )
        val bookings = DatevCsv.bookings(listOf(doc(direction = "OUT", lines = mixed)), config) { null }
        assertEquals(listOf("3", "2", null), bookings.map { it.taxKey })
        assertEquals(listOf("119.00", "10.70", "50.00"), bookings.map { it.amount.toPlainString() })
        assertEquals(3, bookings.map { it.guid }.toSet().size, "a stable GUID per line")
        assertEquals("FR12345678901", bookings[0].euVatId, "intra-EU supply: the buyer's VAT ID")
        assertNull(DatevCsv.bookings(listOf(doc(lines = emptyList())), config) { null }.single().taxKey)
        assertTrue(DatevCsv.bookings(listOf(doc(gross = null, lines = emptyList())), config) { null }.isEmpty())
    }

    @Test
    fun `Belegfeld 1 keeps only allowed characters`() {
        assertEquals("R-2026/001", DatevCsv.documentField("R-2026/001"))
        assertEquals("RE2026_1".filter { it != '_' }, DatevCsv.documentField("RE 2026_1"))
        assertEquals(36, DatevCsv.documentField("X".repeat(50)).length)
    }

    @Test
    fun `fiscal years split a period`() {
        val years = DatevCsv.fiscalYears(LocalDate.of(2026, 5, 1), LocalDate.of(2027, 8, 31), startMonth = 7)
        assertEquals(
            listOf(
                Triple(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 30)),
                Triple(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30)),
                Triple(LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 1), LocalDate.of(2027, 8, 31)),
            ),
            years,
        )
    }

    @Test
    fun `the file has the EXTF header, 125 columns, Windows-1252 and CRLF`() {
        val bookings = DatevCsv.bookings(listOf(doc(direction = "OUT")), config) { "Belege/Ausgang/r.xml" }
        val bytes =
            DatevCsv.write(
                config,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                bookings,
                LocalDateTime.of(2026, 4, 2, 9, 30, 15, 123_000_000),
                "ERIKA März",
            )
        val text = String(bytes, DatevCsv.CHARSET)
        assertTrue(text.endsWith("\r\n"))
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        assertEquals(
            "\"EXTF\";700;21;\"Buchungsstapel\";13;20260402093015123;;\"RE\";\"ERIKA\";\"\";1234567;10001;20260101;4;20260301;20260331;" +
                "\"ERIKA März\";\"\";1;0;0;\"EUR\";;\"\";;;\"03\";;;\"\";\"\"",
            lines[0],
        )
        assertEquals(125, DatevCsv.COLUMNS.size)
        assertEquals(125, lines[1].split(";").size)
        val row = lines[2].split(";")
        assertEquals(125, row.size)
        assertEquals(listOf("119,00", "\"S\"", "\"EUR\""), row.take(3))
        assertEquals("0403", row[9])
        assertEquals("\"R-2026/001\"", row[10])
        assertEquals("\"Kunde \"\"AG\"\"\"", row[13])
        assertEquals("\"Belege/Ausgang/r.xml\"", row[21])
        assertEquals("0", row[113], "not committed (Festschreibung 0)")
        assertTrue(bytes.contains(0xE4.toByte()), "ä as a single Windows-1252 byte")
    }
}
