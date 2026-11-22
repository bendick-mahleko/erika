package de.erika.audit

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuditEntryTest {
    private val entry =
        AuditEntry(
            workspaceId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            seq = 1,
            at = Instant.parse("2026-11-22T10:15:30.123456Z"),
            actor = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            action = "identity.login.succeeded",
            details = mapOf("newDevice" to "true", "method" to "totp"),
        )

    @Test
    fun `canonical form is fixed - sorted keys, no whitespace`() {
        assertEquals(
            """{"action":"identity.login.succeeded","actor":"00000000-0000-0000-0000-000000000002",""" +
                """"at":"2026-11-22T10:15:30.123456Z","details":{"method":"totp","newDevice":"true"},""" +
                """"seq":1,"workspace":"00000000-0000-0000-0000-000000000001"}""",
            entry.canonical(),
        )
    }

    @Test
    fun `hash is SHA-256 over previous hash and canonical form`() {
        // Pinned: if this changes, every stored chain would fail verification.
        assertEquals(
            "2d3a286bd6ef786cab6337a7d33e05150e56dfc70c558af9b1882f22a22df553",
            HexFormat.of().formatHex(entry.hash(AuditEntry.GENESIS)),
        )
        val h1 = entry.hash(AuditEntry.GENESIS)
        assertFalse(h1.contentEquals(entry.copy(details = mapOf("method" to "recovery_code")).hash(AuditEntry.GENESIS)))
        assertFalse(h1.contentEquals(entry.hash(ByteArray(32) { 1 })))
        assertFalse(h1.contentEquals(entry.copy(actor = null).hash(AuditEntry.GENESIS)))
    }
}
