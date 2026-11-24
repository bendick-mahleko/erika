package de.erika.documents

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GiroCodeTest {
    @Test
    fun `payload follows EPC069-12 version 002`() {
        val t =
            GiroCode.transferOf(
                "Muster GmbH",
                "Verkäufer",
                "de02 1203 0000 0000 2020 51",
                "BYLADEM1001",
                BigDecimal("1234.5"),
                "EUR",
                null,
                "RE-2026-0001",
            )!!
        assertEquals(
            "BCD\n002\n1\nSCT\nBYLADEM1001\nMuster GmbH\nDE02120300000000202051\nEUR1234.50\n\n\nRE-2026-0001",
            t.epcPayload,
        )
    }

    @Test
    fun `seller name and missing amount and BIC are allowed, line breaks are removed`() {
        val t = GiroCode.transferOf(null, "Verkäufer\nAG", "DE02120300000000202051", null, null, null, "Rechnung\r\n42", null)!!
        assertEquals(
            listOf("BCD", "002", "1", "SCT", "", "Verkäufer AG", "DE02120300000000202051", "", "", "", "Rechnung  42"),
            t.epcPayload.split("\n"),
        )
    }

    @Test
    fun `no transfer without a valid IBAN, a name or in another currency`() {
        assertNull(GiroCode.transferOf("A", null, "keine IBAN", null, BigDecimal.ONE, "EUR", null, null))
        assertNull(GiroCode.transferOf(null, null, "DE02120300000000202051", null, BigDecimal.ONE, "EUR", null, null))
        assertNull(GiroCode.transferOf("A", null, "DE02120300000000202051", null, BigDecimal.ONE, "USD", null, null))
    }
}
