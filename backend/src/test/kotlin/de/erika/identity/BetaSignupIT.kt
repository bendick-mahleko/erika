package de.erika.identity

import de.erika.support.ApiIntegrationTest
import de.erika.support.Mails
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Closed beta (ADR 0030): sign-up only with a valid invitation for exactly this address. */
@TestPropertySource(properties = ["erika.signup.mode=INVITE_ONLY", "erika.signup.invite-secret=$SECRET"])
class BetaSignupIT : ApiIntegrationTest() {
    private val invitations = BetaInvitations(SECRET)

    private fun signup(
        email: String,
        code: String?,
    ) = client().post(
        "/api/v1/auth/signup",
        buildMap {
            put("email", email)
            put("password", "a long enough passphrase")
            put("displayName", "Beta")
            put("workspaceName", "Beta GmbH")
            put("termsAccepted", true)
            code?.let { put("invitationCode", it) }
        },
    )

    @Test
    fun `the policy reports invite-only`() {
        assertEquals(
            true,
            client()
                .get("/api/v1/auth/signup-policy")
                .tree
                .path("inviteOnly")
                .asBoolean(),
        )
    }

    @Test
    fun `without an invitation, sign-up is refused and nothing is created`() {
        val email = "nobody-${UUID.randomUUID()}@example.test"
        val response = signup(email, null)
        assertEquals(403, response.status)
        assertEquals("INVITATION_REQUIRED", response.code)
        assertTrue(Mails.queuedTo(jdbc, email).isEmpty())
    }

    @Test
    fun `a valid invitation works for its address, in any letter case`() {
        val email = "Invited-${UUID.randomUUID()}@Example.test"
        val code = invitations.create(email.lowercase(), Instant.now().plus(14, ChronoUnit.DAYS))
        assertEquals(202, signup(email, code).status)
        assertTrue(Mails.queuedTo(jdbc, email.trim()).isNotEmpty(), "verification mail queued")
    }

    @Test
    fun `an invitation for another address, an expired one or a forged one is refused`() {
        val invited = "invited-${UUID.randomUUID()}@example.test"
        val valid = invitations.create(invited, Instant.now().plus(1, ChronoUnit.DAYS))

        val other = signup("other-${UUID.randomUUID()}@example.test", valid)
        assertEquals("INVITATION_INVALID", other.code)

        val expired = signup(invited, invitations.create(invited, Instant.now().minusSeconds(1)))
        assertEquals("INVITATION_INVALID", expired.code)

        val forged =
            signup(invited, BetaInvitations("another-secret-with-at-least-32-chars!").create(invited, Instant.now().plusSeconds(3600)))
        assertEquals(403, forged.status)
        assertEquals("INVITATION_INVALID", forged.code)

        assertEquals("INVITATION_INVALID", signup(invited, "garbage").code)
    }
}

private const val SECRET = "test-invite-secret-0123456789abcdef"
