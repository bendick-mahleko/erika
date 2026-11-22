package de.erika.identity

import de.erika.support.ApiIntegrationTest
import de.erika.support.Mails
import de.erika.support.TestDatabase
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 3 exit criterion "login, TOTP and lockout covered by integration tests"
 * (EX-02 core, NF-02): the whole sign-in flow over HTTP with sessions and CSRF.
 */
class AuthFlowIT : ApiIntegrationTest() {
    @Test
    fun `sign-up, email verification, password, TOTP enrolment, sign-out`() {
        val c = client()
        assertEquals(
            "ANONYMOUS",
            c
                .get("/api/v1/auth/session")
                .tree
                .path("state")
                .asString(),
        )
        assertNotNull(c.cookie("XSRF-TOKEN"), "GET /auth/session must set the CSRF cookie")

        val user = accounts.registered("flow")
        val login = c.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        assertEquals(200, login.status, login.toString())
        assertEquals("TOTP_ENROLMENT_REQUIRED", login.tree.path("state").asString())

        // Not signed in yet: the password step grants nothing.
        assertEquals(401, c.get("/api/v1/workspace").status)

        val enrolment = c.post("/api/v1/auth/totp/enrolment")
        assertEquals(200, enrolment.status)
        assertTrue(
            enrolment.tree
                .path("otpauthUri")
                .asString()
                .startsWith("otpauth://totp/ERIKA%3A"),
        )
        val secret = Base32.decode(enrolment.tree.path("secret").asString())

        val wrong = c.post("/api/v1/auth/totp/enrolment/confirm", mapOf("code" to "000000"))
        assertEquals(401, wrong.status)
        assertEquals("INVALID_CODE", wrong.code)

        val confirm = c.post("/api/v1/auth/totp/enrolment/confirm", mapOf("code" to accounts.nextCode(user.email, secret)))
        assertEquals(200, confirm.status, confirm.toString())
        assertEquals(10, confirm.tree.path("codes").size())

        val session = c.get("/api/v1/auth/session").tree
        assertEquals("AUTHENTICATED", session.path("state").asString())
        assertEquals("OWNER", session.path("role").asString())
        assertEquals("Workspace flow", session.path("workspace").path("name").asString())
        assertEquals(200, c.get("/api/v1/workspace").status)

        assertEquals(204, c.post("/api/v1/auth/logout").status)
        assertEquals(
            "ANONYMOUS",
            c
                .get("/api/v1/auth/session")
                .tree
                .path("state")
                .asString(),
        )
        assertEquals(401, c.get("/api/v1/workspace").status)
    }

    @Test
    fun `sign-in needs a verified email address`() {
        val c = client()
        val email = "unverified-${UUID.randomUUID()}@example.test"
        val password = "a long enough passphrase"
        c.post("/api/v1/auth/signup", mapOf("email" to email, "password" to password, "displayName" to "U", "workspaceName" to "W"))

        val login = c.post("/api/v1/auth/login", mapOf("email" to email, "password" to password))
        assertEquals(403, login.status)
        assertEquals("EMAIL_NOT_VERIFIED", login.code)
    }

    @Test
    fun `verification links work once`() {
        val c = client()
        val email = "once-${UUID.randomUUID()}@example.test"
        c.post(
            "/api/v1/auth/signup",
            mapOf(
                "email" to email,
                "password" to "a long enough passphrase",
                "displayName" to "U",
                "workspaceName" to "W",
            ),
        )
        val token = Mails.verificationToken(jdbc, email)

        assertEquals(204, c.post("/api/v1/auth/verify-email", mapOf("token" to token)).status)
        val again = c.post("/api/v1/auth/verify-email", mapOf("token" to token))
        assertEquals(400, again.status)
        assertEquals("INVALID_TOKEN", again.code)
    }

    @Test
    fun `sign-up with a registered address answers the same and mails the owner`() {
        val user = accounts.registered("dup")
        val response =
            client().post(
                "/api/v1/auth/signup",
                mapOf(
                    "email" to user.email.uppercase(),
                    "password" to "another long passphrase",
                    "displayName" to "X",
                    "workspaceName" to "Y",
                ),
            )
        assertEquals(202, response.status)
        val mails = Mails.queuedTo(jdbc, user.email)
        assertEquals(2, mails.size, "verification + 'already registered' notice")
        assertEquals("Registrierung mit Ihrer E-Mail-Adresse", mails.last()["subject"])
    }

    @Test
    fun `weak and invalid sign-up input is rejected with stable codes`() {
        val c = client()
        val short =
            c.post(
                "/api/v1/auth/signup",
                mapOf(
                    "email" to "x@example.test",
                    "password" to "short",
                    "displayName" to "U",
                    "workspaceName" to "W",
                ),
            )
        assertEquals(400, short.status)
        assertEquals("VALIDATION_FAILED", short.code)
        assertTrue(!short.body.contains("short"), "rejected values are not echoed")

        val containsEmail =
            c.post(
                "/api/v1/auth/signup",
                mapOf("email" to "me@example.test", "password" to "me@example.test-123", "displayName" to "U", "workspaceName" to "W"),
            )
        assertEquals("WEAK_PASSWORD", containsEmail.code)
    }

    @Test
    fun `wrong password and unknown user look the same`() {
        val user = accounts.enrolled("same")
        val wrong = client().post("/api/v1/auth/login", mapOf("email" to user.email, "password" to "wrong password!!"))
        val unknown = client().post("/api/v1/auth/login", mapOf("email" to "nobody@example.test", "password" to "wrong password!!"))
        assertEquals(401, wrong.status)
        assertEquals(401, unknown.status)
        assertEquals(wrong.code, unknown.code)
        assertEquals("INVALID_CREDENTIALS", wrong.code)
    }

    @Test
    fun `a TOTP code can be used only once`() {
        val user = accounts.enrolled("replay")
        val code = accounts.nextCode(user.email, user.totpSecret!!)

        val first = client()
        first.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        assertEquals(200, first.post("/api/v1/auth/totp", mapOf("code" to code)).status)

        val second = client()
        second.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        val replay = second.post("/api/v1/auth/totp", mapOf("code" to code))
        assertEquals(401, replay.status)
        assertEquals("INVALID_CODE", replay.code)
    }

    @Test
    fun `recovery codes sign in once each`() {
        val user = accounts.enrolled("recovery")
        val code = user.recoveryCodes.first()

        val c = client()
        c.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        val ok = c.post("/api/v1/auth/totp", mapOf("recoveryCode" to code.lowercase().replace("-", " ")))
        assertEquals(200, ok.status, ok.toString())

        val d = client()
        d.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        assertEquals("INVALID_CODE", d.post("/api/v1/auth/totp", mapOf("recoveryCode" to code)).code)
    }

    @Test
    fun `five failures lock the account, also for the right password, and notify the owner`() {
        val user = accounts.enrolled("lockout")
        val c = client()
        repeat(4) {
            assertEquals(401, c.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to "wrong password $it")).status)
        }
        // The fifth failure is a wrong second factor: both count.
        c.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        assertEquals(401, c.post("/api/v1/auth/totp", mapOf("code" to "000000")).status)

        val locked = client().post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        assertEquals(401, locked.status)
        assertEquals("INVALID_CREDENTIALS", locked.code, "a locked account looks like wrong credentials")

        assertTrue(Mails.queuedTo(jdbc, user.email).any { it["subject"] == "Ihr ERIKA-Konto wurde vorübergehend gesperrt" })
        val actions = auditActions(user.workspaceId)
        assertEquals(5, actions.count { it == "identity.login.failed" })
        assertTrue("identity.account.locked" in actions)

        // After the lock expires, the right credentials work again.
        TestDatabase.update("UPDATE app_user SET locked_until = now() - interval '1 second' WHERE id = ?", user.userId)
        accounts.signIn(user)
    }

    @Test
    fun `the session id changes when the password is accepted and again after the second factor`() {
        val user = accounts.enrolled("fixation")
        val c = client()
        c.post("/api/v1/auth/login", mapOf("email" to "nobody@example.test", "password" to "irrelevant!!")) // creates no session
        c.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        val afterPassword = c.cookie("ERIKA_SESSION")
        assertNotNull(afterPassword)
        c.post("/api/v1/auth/totp", mapOf("code" to accounts.nextCode(user.email, user.totpSecret!!)))
        assertNotEquals(afterPassword, c.cookie("ERIKA_SESSION"))
    }

    @Test
    fun `state-changing requests without the CSRF token are rejected`() {
        val c = client()
        val response = c.send("POST", "/api/v1/auth/login", mapOf("email" to "a@example.test", "password" to "irrelevant!!"), csrf = false)
        assertEquals(403, response.status)
        assertEquals("CSRF_TOKEN_INVALID", response.code)
    }

    @Test
    fun `a sign-in from a new device sends a notification, a known device does not`() {
        val user = accounts.enrolled("device")
        val before = Mails.queuedTo(jdbc, user.email).size

        val known = client()
        known.setCookie("erika_device", "x".repeat(43)) // unknown token: counts as new device
        accounts.signIn(user, known)
        val afterFirst = Mails.queuedTo(jdbc, user.email).map { it["subject"] }
        assertEquals(before + 1, afterFirst.size, afterFirst.toString())
        assertEquals("Neue Anmeldung bei ERIKA", afterFirst.last())

        accounts.signIn(user, known) // same device again
        val afterSecond = Mails.queuedTo(jdbc, user.email).map { it["subject"] }
        assertEquals(before + 1, afterSecond.size, afterSecond.toString())
    }

    @Test
    fun `more than 20 attempts per client IP within 5 minutes are rate limited`() {
        val c = client()
        val statuses = (1..21).map { c.post("/api/v1/auth/login", mapOf("email" to "rate@example.test", "password" to "irrelevant!!")) }
        assertTrue(statuses.take(20).all { it.status == 401 })
        assertEquals(429, statuses.last().status)
        assertEquals("RATE_LIMITED", statuses.last().code)
        // Another client IP is not affected.
        assertEquals(401, client().post("/api/v1/auth/login", mapOf("email" to "rate@example.test", "password" to "irrelevant!!")).status)
    }

    @Test
    fun `the second factor needs the password step first`() {
        val response = client().post("/api/v1/auth/totp", mapOf("code" to "123456"))
        assertEquals(401, response.status)
        assertEquals("LOGIN_REQUIRED", response.code)
    }

    @Test
    fun `secrets are stored hashed or encrypted`() {
        val user = accounts.enrolled("storage")
        TestDatabase.asOwner { c ->
            c.prepareStatement("SELECT password_hash, totp_secret FROM app_user WHERE id = ?").use { ps ->
                ps.setObject(1, user.userId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    assertTrue(rs.getString(1).startsWith("\$argon2id\$"))
                    assertTrue(rs.getString(2).startsWith("v1:"))
                    assertTrue(!rs.getString(2).contains(Base32.encode(user.totpSecret!!)))
                }
            }
        }
        // Recovery codes: only 32-byte SHA-256 digests, never the code text.
        val lengths =
            TestDatabase.asOwner { c ->
                c
                    .prepareStatement("SELECT octet_length(code_hash) FROM recovery_code WHERE user_id = ?")
                    .apply { setObject(1, user.userId) }
                    .executeQuery()
                    .use { rs -> generateSequence { if (rs.next()) rs.getInt(1) else null }.toList() }
            }
        assertEquals(List(10) { 32 }, lengths)
    }

    private fun auditActions(workspaceId: UUID): List<String> =
        TestDatabase.asOwner { c ->
            c.prepareStatement("SELECT action FROM audit_log WHERE workspace_id = ? ORDER BY seq").use { ps ->
                ps.setObject(1, workspaceId)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }
}
