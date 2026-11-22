package de.erika.identity

import de.erika.platform.security.WorkspaceRole
import de.erika.support.ApiIntegrationTest
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Workspace settings with optimistic locking, and the role rules (EX-02: advisors only read). */
class WorkspaceIT : ApiIntegrationTest() {
    @Autowired lateinit var passwords: PasswordHasher

    private val update =
        mapOf(
            "name" to "Malerbetrieb Muster",
            "street" to "Musterstraße 1",
            "postalCode" to "10115",
            "city" to "Berlin",
            "countryCode" to "DE",
            "vatId" to "de123456789",
            "taxNumber" to "30/123/45678",
        )

    @Test
    fun `owner reads and changes settings with If-Match`() {
        val c = client()
        val owner = accounts.enrolled("settings", c)

        val get = c.get("/api/v1/workspace")
        assertEquals(200, get.status)
        val etag = get.header("ETag")!!

        assertEquals("PRECONDITION_REQUIRED", c.put("/api/v1/workspace", update).code)
        assertEquals("VERSION_CONFLICT", c.put("/api/v1/workspace", update, "If-Match" to "\"999\"").code)

        val put = c.put("/api/v1/workspace", update, "If-Match" to etag)
        assertEquals(200, put.status, put.toString())
        assertEquals("DE123456789", put.tree.path("vatId").asString(), "VAT ID normalised")
        assertEquals("Berlin", put.tree.path("city").asString())
        assertTrue(put.header("ETag") != etag)

        // The old ETag is now outdated.
        assertEquals(412, c.put("/api/v1/workspace", update, "If-Match" to etag).status)

        val audit =
            TestDatabase.asOwner { conn ->
                conn
                    .prepareStatement(
                        "SELECT payload->>'fields' FROM audit_log WHERE workspace_id = ? AND action = 'identity.workspace.updated'",
                    ).use {
                        it.setObject(1, owner.workspaceId)
                        it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
            }
        assertEquals("address,name,taxNumber,vatId", audit)
    }

    @Test
    fun `invalid settings are rejected`() {
        val c = client()
        accounts.enrolled("invalid", c)
        val etag = c.get("/api/v1/workspace").header("ETag")!!
        val bad = c.put("/api/v1/workspace", update + ("vatId" to "not a vat id"), "If-Match" to etag)
        assertEquals(400, bad.status)
        assertEquals("VALIDATION_FAILED", bad.code)
    }

    @Test
    fun `a tax advisor can read but not change anything`() {
        val owner = accounts.enrolled("advised")
        val advisor = signedInMember(owner, WorkspaceRole.ADVISOR)

        val get = advisor.get("/api/v1/workspace")
        assertEquals(200, get.status)
        assertEquals(
            "ADVISOR",
            advisor
                .get("/api/v1/auth/session")
                .tree
                .path("role")
                .asString(),
        )

        val put = advisor.put("/api/v1/workspace", update, "If-Match" to get.header("ETag")!!)
        assertEquals(403, put.status)
        assertEquals("FORBIDDEN", put.code)
    }

    @Test
    fun `a member cannot change workspace settings (owner only)`() {
        val owner = accounts.enrolled("membered")
        val member = signedInMember(owner, WorkspaceRole.MEMBER)
        val get = member.get("/api/v1/workspace")
        val put = member.put("/api/v1/workspace", update, "If-Match" to get.header("ETag")!!)
        assertEquals(403, put.status)
        assertEquals("FORBIDDEN", put.code)
    }

    @Test
    fun `members are listed for the own workspace only`() {
        val c = client()
        val owner = accounts.enrolled("listing", c)
        signedInMember(owner, WorkspaceRole.MEMBER)

        val list = c.get("/api/v1/members")
        assertEquals(200, list.status)
        val roles =
            list.tree
                .path("items")
                .values()
                .map { it.path("role").asString() }
                .sorted()
        assertEquals(listOf("MEMBER", "OWNER"), roles)
        val memberId =
            list.tree
                .path("items")
                .first()
                .path("id")
                .asString()
        assertEquals(200, c.get("/api/v1/members/$memberId").status)
        assertEquals(404, c.get("/api/v1/members/${UUID.randomUUID()}").status)
    }

    /**
     * A second user in [owner]'s workspace with [role]. Invitations come in Stage 9, so the
     * user and membership are created directly in the database (as the schema owner).
     */
    private fun signedInMember(
        owner: TestUser,
        role: WorkspaceRole,
    ): TestClient {
        val email = "${role.name.lowercase()}-${UUID.randomUUID().toString().take(8)}@example.test"
        val password = "member passphrase ${UUID.randomUUID()}"
        val userId = UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO app_user (id, email, display_name, password_hash, email_verified_at) VALUES (?, ?, ?, ?, now())",
            userId,
            email,
            "Test ${role.name}",
            passwords.hash(password),
        )
        TestDatabase.update(
            "INSERT INTO membership (id, workspace_id, user_id, role) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(),
            owner.workspaceId,
            userId,
            role.name,
        )
        val c = client()
        val login = c.post("/api/v1/auth/login", mapOf("email" to email, "password" to password))
        assertEquals("TOTP_ENROLMENT_REQUIRED", login.tree.path("state").asString(), "2FA is mandatory for every role (NF-02)")
        val secret =
            Base32.decode(
                c
                    .post("/api/v1/auth/totp/enrolment")
                    .tree
                    .path("secret")
                    .asString(),
            )
        assertEquals(200, c.post("/api/v1/auth/totp/enrolment/confirm", mapOf("code" to accounts.nextCode(email, secret))).status)
        return c
    }
}
