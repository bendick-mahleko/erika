package de.erika.invoicecore

import de.erika.invoicecore.model.InvoiceFormat
import de.erika.invoicecore.model.NotEInvoiceReason
import de.erika.invoicecore.support.Fixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NF-04 and Software Design §7: hostile and broken input is rejected without harm. */
class SecurityAndRobustnessTest {
    private fun code(bytes: ByteArray): ParseErrorCode = assertFailsWith<InvoiceParseException> { InvoiceParser.parse(bytes) }.code

    @Test
    fun `XXE with an external entity is rejected`() {
        val xxe =
            """
            <?xml version="1.0"?>
            <!DOCTYPE inv [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                     xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
              <cbc:ID>&xxe;</cbc:ID>
            </Invoice>
            """.trimIndent()
        assertEquals(ParseErrorCode.FORBIDDEN_XML_CONSTRUCT, code(xxe.toByteArray()))
    }

    @Test
    fun `billion laughs is rejected`() {
        val entities = (1..9).joinToString("\n") { i -> "<!ENTITY lol$i \"${"&lol${i - 1};".repeat(10)}\">" }
        val bomb = "<?xml version=\"1.0\"?>\n<!DOCTYPE lolz [<!ENTITY lol0 \"lol\">\n$entities]>\n<lolz>&lol9;</lolz>"
        assertEquals(ParseErrorCode.FORBIDDEN_XML_CONSTRUCT, code(bomb.toByteArray()))
    }

    @Test
    fun `size and line limits are enforced`() {
        assertEquals(ParseErrorCode.TOO_LARGE, code(ByteArray(InvoiceParser.MAX_FILE_BYTES + 1) { ' '.code.toByte() }))

        val lines = "<cac:InvoiceLine><cbc:ID>1</cbc:ID></cac:InvoiceLine>".repeat(InvoiceParser.MAX_LINES + 1)
        val ubl =
            """<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                 xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2"
                 xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2">$lines</Invoice>"""
        assertEquals(ParseErrorCode.TOO_MANY_LINES, code(ubl.toByteArray()))
    }

    @Test
    fun `broken input gets a precise error code`() {
        assertEquals(ParseErrorCode.UNKNOWN_FILE_TYPE, code("just text".toByteArray()))
        assertEquals(ParseErrorCode.UNKNOWN_FILE_TYPE, code(ByteArray(0)))
        assertEquals(ParseErrorCode.MALFORMED_XML, code("<Invoice><unclosed></Invoice>".toByteArray()))
        assertEquals(ParseErrorCode.MALFORMED_PDF, code("%PDF-1.7\nthis is not a pdf".toByteArray()))
    }

    @Test
    fun `unknown XML is unsupported, not an error`() {
        val parsed = InvoiceParser.parse("<order xmlns=\"urn:example\"/>".toByteArray())
        assertEquals(InvoiceFormat.UNSUPPORTED, parsed.format.format)
        assertEquals(NotEInvoiceReason.UNKNOWN_XML, parsed.format.reason)
        assertNull(parsed.invoice)
    }

    @Test
    fun `a byte order mark before the XML declaration is accepted`() {
        val bytes = Fixtures.xrechnung("01.01a-INVOICE_ubl.xml").readBytes()
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
        assertNotNull(InvoiceParser.parse(withBom).invoice)
    }

    @Test
    fun `unparseable dates and numbers become warnings, not failures`() {
        val original = Fixtures.xrechnung("01.01a-INVOICE_ubl.xml").readText()
        val broken =
            original
                .replaceFirst(Regex("<cbc:IssueDate>[^<]*</cbc:IssueDate>"), "<cbc:IssueDate>13.04.2018</cbc:IssueDate>")
                .replaceFirst(Regex("(<cbc:PayableAmount[^>]*>)[^<]*<"), "$1zwölf<")
        val parsed = InvoiceParser.parse(broken.toByteArray())
        assertNull(parsed.invoice!!.issueDate)
        assertNull(parsed.invoice!!.totals.amountDue)
        assertEquals(setOf("BT-2", "BT-115"), parsed.warnings.map { it.businessTerm }.toSet())
        assertTrue(parsed.warnings.none { it.message.contains("zwölf") }, "warnings must not echo invoice content")
    }
}
