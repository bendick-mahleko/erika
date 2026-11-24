package de.erika.identity

import de.erika.support.ApiIntegrationTest
import de.erika.support.Mails
import de.erika.support.TestDatabase
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Password reset by email and password change (ASVS 2.1.6, 2.5; ADR 0034). */
class PasswordIT : ApiIntegrationTest() {
    private val newPassword = "ein ganz neuer langer Satz als Passwort"

    private fun resetToken(email: String): String {
        val body = Mails.queuedTo(jdbc, email).last { it["subject"]!!.contains("zurücksetzen") }["body"]!!
        return Regex("token=([A-Za-z0-9_-]+)").find(body)!!.groupValues[1]
    }

    @Test
    fun `a reset link sets a new password once, ends every session and keeps the second factor`() {
        val signedIn = client()
        val user = accounts.enrolled("reset", signedIn)
        val anonymous = client()
        assertEquals(202, anonymous.post("/api/v1/auth/password-reset", mapOf("email" to user.email)).status)
        assertEquals(
            202,
            anonymous.post("/api/v1/auth/password-reset", mapOf("email" to "nobody-${UUID.randomUUID()}@example.test")).status,
        )
        val token = resetToken(user.email)

        val weak = anonymous.post("/api/v1/auth/password-reset/confirm", mapOf("token" to token, "password" to "${user.email}-123"))
        assertEquals("WEAK_PASSWORD", weak.code)
        // A refused password rolls back: the token stays usable.
        assertEquals(204, anonymous.post("/api/v1/auth/password-reset/confirm", mapOf("token" to token, "password" to newPassword)).status)
        assertEquals(
            "INVALID_TOKEN",
            anonymous
                .post(
                    "/api/v1/auth/password-reset/confirm",
                    mapOf(
                        "token" to token,
                        "password" to newPassword,
                    ),
                ).code,
        )

        assertEquals(
            "ANONYMOUS",
            signedIn
                .get("/api/v1/auth/session")
                .tree
                .path("state")
                .asString(),
            "old sessions ended",
        )
        val old = client().post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        assertEquals(401, old.status)
        val fresh = client().post("/api/v1/auth/login", mapOf("email" to user.email, "password" to newPassword))
        assertEquals("TOTP_REQUIRED", fresh.tree.path("state").asString(), "the second factor still applies")
        assertTrue(Mails.queuedTo(jdbc, user.email).any { it["subject"]!!.contains("geändert") })
        assertTrue("identity.password.changed" in audit(user.workspaceId))
    }

    @Test
    fun `a signed-in user changes the password, other sessions end and this one stays`() {
        val here = client()
        val user = accounts.enrolled("change", here)
        val elsewhere = accounts.signIn(user, client())
        val wrong = here.post("/api/v1/auth/password", mapOf("currentPassword" to "falsch", "newPassword" to newPassword))
        assertEquals("CURRENT_PASSWORD_WRONG", wrong.code)
        assertEquals(
            204,
            here.post("/api/v1/auth/password", mapOf("currentPassword" to user.password, "newPassword" to newPassword)).status,
        )
        assertEquals(
            "AUTHENTICATED",
            here
                .get("/api/v1/auth/session")
                .tree
                .path("state")
                .asString(),
        )
        assertEquals(
            "ANONYMOUS",
            elsewhere
                .get("/api/v1/auth/session")
                .tree
                .path("state")
                .asString(),
        )
        assertEquals(401, client().post("/api/v1/auth/password", mapOf("currentPassword" to "x", "newPassword" to newPassword)).status)
        assertTrue("identity.password.changed" in audit(user.workspaceId))
    }

    private fun audit(workspaceId: UUID): List<String> =
        TestDatabase.asOwner {
            it.prepareStatement("SELECT action FROM audit_log WHERE workspace_id = ? ORDER BY seq").run {
                setObject(1, workspaceId)
                executeQuery().run { generateSequence { if (next()) getString(1) else null }.toList() }
            }
        }
}
