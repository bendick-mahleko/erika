package de.erika.feedback

import de.erika.identity.PasswordHasher
import de.erika.support.ApiIntegrationTest
import de.erika.support.Mails
import de.erika.support.TestDatabase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-app feedback (ADR 0030): stored in the workspace, mailed to the team. */
@TestPropertySource(properties = ["erika.feedback.to=beta-team@example.test"])
class FeedbackIT : ApiIntegrationTest() {
    @Autowired lateinit var passwords: PasswordHasher

    @Test
    fun `feedback is stored for the workspace and mailed to the team`() {
        val c = client()
        val user = accounts.enrolled("feedback", c)
        val marker = "Sortierung nach Fälligkeit ${UUID.randomUUID()}"
        val response = c.post("/api/v1/feedback", mapOf("category" to "IDEA", "message" to marker, "page" to "/inbox?q=geheim#x"))
        assertEquals(202, response.status, response.toString())

        val (category, page) =
            TestDatabase.asOwner {
                it
                    .prepareStatement("SELECT category, page FROM feedback WHERE workspace_id = ? AND message = ?")
                    .apply {
                        setObject(1, user.workspaceId)
                        setString(2, marker)
                    }.executeQuery()
                    .run {
                        assertTrue(next(), "feedback stored")
                        getString(1) to getString(2)
                    }
            }
        assertEquals("IDEA", category)
        assertEquals("/inbox", page, "query string and fragment are dropped")

        val mail = Mails.queuedTo(jdbc, "beta-team@example.test").last()
        assertTrue(mail["subject"]!!.startsWith("ERIKA-Feedback (IDEA)"))
        assertTrue(mail["body"]!!.contains(marker))
        assertTrue(mail["body"]!!.contains(user.email), "the team can answer")
    }

    @Test
    fun `invalid feedback is rejected, and only signed-in users can send it`() {
        val c = client()
        accounts.enrolled("feedback-invalid", c)
        val empty = c.post("/api/v1/feedback", mapOf("category" to "IDEA", "message" to ""))
        assertEquals(400, empty.status)
        assertEquals("VALIDATION_FAILED", empty.code)
        assertEquals(400, c.post("/api/v1/feedback", mapOf("category" to "NOPE", "message" to "x")).status)
        assertEquals(401, client().post("/api/v1/feedback", mapOf("category" to "IDEA", "message" to "x")).status)
    }

    @Test
    fun `a tax advisor may send feedback although advisors change nothing else`() {
        val owner = accounts.enrolled("feedback-owner")
        val email = "advisor-${UUID.randomUUID().toString().take(8)}@example.test"
        val password = "advisor passphrase ${UUID.randomUUID()}"
        val userId = UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO app_user (id, email, display_name, password_hash, email_verified_at) VALUES (?, ?, 'Advisor', ?, now())",
            userId,
            email,
            passwords.hash(password),
        )
        TestDatabase.update(
            "INSERT INTO membership (id, workspace_id, user_id, role) VALUES (?, ?, ?, 'ADVISOR')",
            UUID.randomUUID(),
            owner.workspaceId,
            userId,
        )
        val advisor = client()
        advisor.post("/api/v1/auth/login", mapOf("email" to email, "password" to password))
        val secret =
            de.erika.identity.Base32.decode(
                advisor
                    .post("/api/v1/auth/totp/enrolment")
                    .tree
                    .path("secret")
                    .asString(),
            )
        assertEquals(200, advisor.post("/api/v1/auth/totp/enrolment/confirm", mapOf("code" to accounts.nextCode(email, secret))).status)

        assertEquals(202, advisor.post("/api/v1/feedback", mapOf("category" to "PRAISE", "message" to "Sehr übersichtlich")).status)
        assertEquals(
            403,
            advisor.post("/api/v1/documents/status", mapOf("documentIds" to listOf(UUID.randomUUID()), "status" to "PAID")).status,
        )
    }
}
