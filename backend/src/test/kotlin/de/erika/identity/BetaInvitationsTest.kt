package de.erika.identity

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BetaInvitationsTest {
    private val secret = "test-invite-secret-0123456789abcdef"
    private val clock = Clock.fixed(Instant.parse("2029-06-01T00:00:00Z"), ZoneOffset.UTC)
    private val invitations = BetaInvitations(secret, clock)

    /**
     * Produced by the operator script:
     * `ERIKA_INVITE_SECRET=test-invite-secret-0123456789abcdef infra/production/invite.py Anna.Beta@Example.de --expires 1893456000`.
     * If this fails, the script and the back end no longer agree on the format.
     */
    private val fromScript = "v1.YW5uYS5iZXRhQGV4YW1wbGUuZGV8MTg5MzQ1NjAwMA.AcPk94_O6ywYAbRor_0TJu35GIuoFpDdCNNwrcT3Wf4"

    @Test
    fun `the operator script and the back end produce the same invitation`() {
        assertEquals(fromScript, invitations.create("Anna.Beta@Example.de", Instant.ofEpochSecond(1893456000)))
        assertTrue(invitations.isValidFor(fromScript, " anna.beta@example.DE "))
    }

    @Test
    fun `an invitation is bound to its address, its expiry and the secret`() {
        assertFalse(invitations.isValidFor(fromScript, "someone.else@example.de"))
        assertFalse(
            BetaInvitations(
                secret,
                Clock.fixed(Instant.ofEpochSecond(1893456000), ZoneOffset.UTC),
            ).isValidFor(fromScript, "anna.beta@example.de"),
        )
        assertFalse(BetaInvitations("x".repeat(32), clock).isValidFor(fromScript, "anna.beta@example.de"))

        val tampered = fromScript.replace("MTg5MzQ1NjAwMA", "Mjg5MzQ1NjAwMA") // later expiry, same signature
        assertFalse(invitations.isValidFor(tampered, "anna.beta@example.de"))
        listOf("", "v1.", "v1.a", "v1.a.b.c", "v2.abc.def", "v1.!!!.sig").forEach {
            assertFalse(invitations.isValidFor(it, "anna.beta@example.de"), it)
        }
    }

    @Test
    fun `a short secret is rejected`() {
        assertFailsWith<IllegalArgumentException> { BetaInvitations("too-short") }
    }
}
