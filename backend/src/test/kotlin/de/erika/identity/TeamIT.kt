package de.erika.identity

import de.erika.support.ApiIntegrationTest
import de.erika.support.Mails
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stage 9 identity through the real API: invitations by email (EX-02), joining with a new or an
 * existing account, switching workspaces, role changes and removal (sessions end), the last
 * owner, and the account closure (EX-04: read-only, export still possible, intake refused).
 */
class TeamIT : ApiIntegrationTest() {
    private fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    @Test
    fun `an invited tax advisor signs up with the link and lands in the inviting workspace as ADVISOR`() {
        val ownerClient = client()
        val owner = accounts.enrolled("inviting", ownerClient, workspaceName = "Malerbetrieb Einladung")
        val (advisor, advisorClient) = accounts.invited(ownerClient, "ADVISOR")

        assertEquals(owner.workspaceId, advisor.workspaceId, "no workspace of their own")
        val session = advisorClient.get("/api/v1/auth/session").tree
        assertEquals("ADVISOR", session.path("role").asString())
        assertEquals("Malerbetrieb Einladung", session.path("workspace").path("name").asString())
        assertEquals(1, session.path("workspaces").size())

        val invitations = ownerClient.get("/api/v1/invitations").tree.path("items")
        val mine = invitations.values().single { it.path("email").asString() == advisor.email }
        assertEquals("ACCEPTED", mine.path("state").asString())
        val members =
            ownerClient.get("/api/v1/members").tree.path("items").values().map {
                it.path("email").asString() to
                    it.path("role").asString()
            }
        assertTrue(advisor.email to "ADVISOR" in members, "$members")
        assertTrue(auditActions(owner.workspaceId).containsAll(listOf("identity.invitation.sent", "identity.invitation.accepted")))
    }

    @Test
    fun `an existing user accepts with their account, then switches between their workspaces`() {
        val ownerClient = client()
        val owner = accounts.enrolled("host", ownerClient, workspaceName = "Gastgeber GmbH")
        val guestClient = client()
        val guest = accounts.enrolled("guest", guestClient, workspaceName = "Eigene Kanzlei")

        ownerClient.post("/api/v1/invitations", mapOf("email" to guest.email.uppercase(), "role" to "MEMBER"), key()).also {
            assertEquals(201, it.status, it.toString())
        }
        val token = Mails.verificationToken(jdbc, guest.email.uppercase())
        val preview = client().post("/api/v1/auth/invitation", mapOf("token" to token))
        assertEquals(200, preview.status, preview.toString())
        assertEquals("Gastgeber GmbH", preview.tree.path("workspaceName").asString())
        assertTrue(preview.tree.path("accountExists").asBoolean())
        assertEquals("OPEN", preview.tree.path("state").asString())

        val accepted = guestClient.post("/api/v1/auth/invitation/accept", mapOf("token" to token))
        assertEquals(200, accepted.status, accepted.toString())
        assertEquals(
            "Gastgeber GmbH",
            accepted.tree
                .path("workspace")
                .path("name")
                .asString(),
        )
        assertEquals("MEMBER", accepted.tree.path("role").asString())
        assertEquals(2, accepted.tree.path("workspaces").size())
        // Accepting again is harmless.
        assertEquals(200, guestClient.post("/api/v1/auth/invitation/accept", mapOf("token" to token)).status)

        val back = guestClient.post("/api/v1/auth/workspace", mapOf("workspaceId" to guest.workspaceId.toString()))
        assertEquals(200, back.status, back.toString())
        assertEquals("OWNER", back.tree.path("role").asString())
        assertEquals(
            "Eigene Kanzlei",
            guestClient
                .get("/api/v1/workspace")
                .tree
                .path("name")
                .asString(),
        )

        val foreign = guestClient.post("/api/v1/auth/workspace", mapOf("workspaceId" to UUID.randomUUID().toString()))
        assertEquals(404, foreign.status)
        val again = guestClient.post("/api/v1/auth/workspace", mapOf("workspaceId" to owner.workspaceId.toString()))
        assertEquals(
            "Gastgeber GmbH",
            again.tree
                .path("workspace")
                .path("name")
                .asString(),
        )
    }

    @Test
    fun `invitations are bound to the address, can be withdrawn, and members can't be invited twice`() {
        val ownerClient = client()
        accounts.enrolled("strict", ownerClient)
        val otherClient = client()
        accounts.enrolled("other", otherClient)

        val email = "bound-${UUID.randomUUID().toString().take(8)}@example.test"
        val created = ownerClient.post("/api/v1/invitations", mapOf("email" to email, "role" to "ADVISOR"), key())
        val token = Mails.verificationToken(jdbc, email)
        val wrong = otherClient.post("/api/v1/auth/invitation/accept", mapOf("token" to token))
        assertEquals(403, wrong.status)
        assertEquals("INVITATION_OTHER_ACCOUNT", wrong.code)

        assertEquals(204, ownerClient.send("DELETE", "/api/v1/invitations/${created.tree.path("id").asString()}", null).status)
        assertEquals(
            "REVOKED",
            client()
                .post("/api/v1/auth/invitation", mapOf("token" to token))
                .tree
                .path("state")
                .asString(),
        )
        val signup =
            client().post(
                "/api/v1/auth/signup",
                mapOf(
                    "email" to email,
                    "password" to "a long passphrase ${UUID.randomUUID()}",
                    "displayName" to "X",
                    "termsAccepted" to true,
                    "workspaceInvitation" to token,
                ),
            )
        assertEquals(410, signup.status, signup.toString())
        assertEquals("INVITATION_NOT_OPEN", signup.code)

        val (member, _) = accounts.invited(ownerClient, "MEMBER")
        val twice = ownerClient.post("/api/v1/invitations", mapOf("email" to member.email, "role" to "ADVISOR"), key())
        assertEquals(409, twice.status)
        assertEquals("ALREADY_MEMBER", twice.code)
        assertEquals(404, client().post("/api/v1/auth/invitation", mapOf("token" to "unknown")).status)
    }

    @Test
    fun `a role change or removal ends the member's session, and the last owner stays`() {
        val ownerClient = client()
        val owner = accounts.enrolled("roles", ownerClient)
        val (member, memberClient) = accounts.invited(ownerClient, "MEMBER")
        val memberId = memberIdOf(ownerClient, member.email)

        val demoted = ownerClient.send("PATCH", "/api/v1/members/$memberId", mapOf("role" to "ADVISOR"))
        assertEquals(200, demoted.status, demoted.toString())
        assertEquals("ADVISOR", demoted.tree.path("role").asString())
        val write = memberClient.post("/api/v1/customers", mapOf("name" to "X", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"), key())
        assertEquals(401, write.status, write.toString())
        assertEquals("SESSION_REVOKED", write.code)
        assertEquals(
            "ANONYMOUS",
            memberClient
                .get("/api/v1/auth/session")
                .tree
                .path("state")
                .asString(),
        )

        // Signed in again, the member has the new role: reading yes, writing no.
        accounts.signIn(member, memberClient)
        assertEquals(
            "ADVISOR",
            memberClient
                .get("/api/v1/auth/session")
                .tree
                .path("role")
                .asString(),
        )
        assertEquals(403, memberClient.post("/api/v1/customers", mapOf("name" to "X"), key()).status)

        val ownerMemberId = memberIdOf(ownerClient, owner.email)
        val lastOwner = ownerClient.send("PATCH", "/api/v1/members/$ownerMemberId", mapOf("role" to "MEMBER"))
        assertEquals(409, lastOwner.status)
        assertEquals("LAST_OWNER", lastOwner.code)
        assertEquals("LAST_OWNER", ownerClient.send("DELETE", "/api/v1/members/$ownerMemberId", null).code)

        assertEquals(204, ownerClient.send("DELETE", "/api/v1/members/$memberId", null).status)
        assertEquals(401, memberClient.get("/api/v1/documents").status)
        assertTrue(auditActions(owner.workspaceId).containsAll(listOf("identity.member.role_changed", "identity.member.removed")))
    }

    @Test
    fun `a closed account is read-only, can still be exported, and its intake address refuses mail (EX-04)`() {
        val ownerClient = client()
        val owner = accounts.enrolled("closing", ownerClient, workspaceName = "Schließt GmbH")
        val (_, memberClient) = accounts.invited(ownerClient, "MEMBER")
        val (_, advisorClient) = accounts.invited(ownerClient, "ADVISOR")
        val address =
            ownerClient
                .get("/api/v1/intake/address")
                .tree
                .path("address")
                .asString()
        assertTrue(intakeKnows(address))

        assertEquals(403, memberClient.post("/api/v1/workspace/closure", mapOf("confirmName" to "Schließt GmbH")).status, "owners only")
        assertEquals(403, advisorClient.post("/api/v1/workspace/closure", mapOf("confirmName" to "Schließt GmbH")).status)
        val mismatch = ownerClient.post("/api/v1/workspace/closure", mapOf("confirmName" to "Schliesst GmbH"))
        assertEquals(400, mismatch.status)
        assertEquals("CONFIRMATION_MISMATCH", mismatch.code)

        val closed = ownerClient.post("/api/v1/workspace/closure", mapOf("confirmName" to "Schließt GmbH"))
        assertEquals(200, closed.status, closed.toString())
        assertTrue(closed.tree.path("closedAt").isString)
        assertTrue(closed.tree.path("deleteAfter").isString)
        assertTrue(
            ownerClient
                .get("/api/v1/auth/session")
                .tree
                .path("workspace")
                .path("closed")
                .asBoolean(),
        )

        // Reading works for everyone; changes don't, not even for the owner.
        assertEquals(200, memberClient.get("/api/v1/documents").status)
        val etag = ownerClient.get("/api/v1/workspace").header("ETag")!!
        val update = ownerClient.put("/api/v1/workspace", mapOf("name" to "Neu", "countryCode" to "DE"), "If-Match" to etag)
        assertEquals(403, update.status)
        assertEquals("WORKSPACE_CLOSED", update.code)
        assertEquals(
            "WORKSPACE_CLOSED",
            memberClient
                .post(
                    "/api/v1/customers",
                    mapOf(
                        "name" to "X",
                        "countryCode" to "DE",
                        "vatTreatment" to "DOMESTIC",
                    ),
                    key(),
                ).code,
        )
        assertEquals(
            "WORKSPACE_CLOSED",
            ownerClient.post("/api/v1/invitations", mapOf("email" to "x@example.test", "role" to "MEMBER"), key()).code,
        )
        assertEquals("WORKSPACE_CLOSED", ownerClient.post("/api/v1/workspace/closure", mapOf("confirmName" to "Schließt GmbH")).code)

        // Taking the data out is still possible.
        val export = ownerClient.post("/api/v1/exports", mapOf("kind" to "FULL"), key())
        assertEquals(202, export.status, export.toString())
        assertFalse(intakeKnows(address), "mail to a closed workspace is refused")
        assertTrue("identity.workspace.closed" in auditActions(owner.workspaceId))
        val row =
            TestDatabase.asOwner {
                it.prepareStatement("SELECT closed_by, delete_after >= current_date FROM workspace WHERE id = ?").run {
                    setObject(1, owner.workspaceId)
                    executeQuery().run {
                        next()
                        getObject(1, UUID::class.java) to getBoolean(2)
                    }
                }
            }
        assertEquals(owner.userId to true, row)
    }

    private fun memberIdOf(
        owner: TestClient,
        email: String,
    ): String =
        owner
            .get("/api/v1/members")
            .tree
            .path("items")
            .values()
            .single { it.path("email").asString() == email }
            .path("id")
            .asString()

    private fun intakeKnows(address: String): Boolean {
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/internal/intake/recipients/check"))
                    .header("Content-Type", "application/json")
                    .header("X-Intake-Secret", "test-intake-secret")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"recipient":"$address"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        check(response.statusCode() == 200) { "intake check: ${response.statusCode()} ${response.body()}" }
        return json.readTree(response.body()).path("known").asBoolean()
    }

    private fun auditActions(workspaceId: UUID): List<String> =
        TestDatabase.asOwner {
            it.prepareStatement("SELECT action FROM audit_log WHERE workspace_id = ? ORDER BY seq").run {
                setObject(1, workspaceId)
                executeQuery().run { generateSequence { if (next()) getString(1) else null }.toList() }
            }
        }
}
