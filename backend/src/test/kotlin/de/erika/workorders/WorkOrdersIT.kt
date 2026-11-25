package de.erika.workorders

import de.erika.support.ApiIntegrationTest
import de.erika.support.Mails
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestUser
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MARA Stage 2: technicians, master data, work orders (M-OF-01 to M-OF-05, M-AC-01, ADR 0039). */
class WorkOrdersIT : ApiIntegrationTest() {
    private fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    /** A technician through the real invitation flow: invited, signed up, email verified. No web session. */
    private fun technician(
        owner: TestClient,
        prefix: String = "techniker",
    ): TestUser {
        val email = "$prefix-${UUID.randomUUID().toString().take(8)}@example.test"
        val invite = owner.post("/api/v1/invitations", mapOf("email" to email, "role" to "TECHNICIAN"), key())
        assertEquals(201, invite.status, "$invite")
        assertEquals("TECHNICIAN", invite.tree.path("role").asString())
        val password = "Passphrase for $prefix ${UUID.randomUUID()}"
        val client = client()
        val signup =
            client.post(
                "/api/v1/auth/signup",
                mapOf(
                    "email" to email,
                    "password" to password,
                    "displayName" to "Test $prefix",
                    "termsAccepted" to true,
                    "workspaceInvitation" to Mails.verificationToken(jdbc, email),
                ),
            )
        assertEquals(202, signup.status, "$signup")
        assertEquals(204, client.post("/api/v1/auth/verify-email", mapOf("token" to Mails.verificationToken(jdbc, email))).status)
        val userId =
            TestDatabase.asOwner {
                it.prepareStatement("SELECT id FROM app_user WHERE email = ?").run {
                    setString(1, email)
                    executeQuery().run {
                        next()
                        getObject(1, UUID::class.java)
                    }
                }
            }
        return TestUser(email, password, userId, UUID(0, 0), null)
    }

    private fun customer(
        c: TestClient,
        name: String = "Wohnbau Petersen GmbH",
    ): String {
        val r = c.post("/api/v1/customers", mapOf("name" to name, "countryCode" to "DE", "vatTreatment" to "DOMESTIC"), key())
        assertEquals(201, r.status, "$r")
        return r.tree.path("id").asString()
    }

    private fun auditActions(workspaceId: UUID): List<String> =
        TestDatabase.asOwner {
            it.prepareStatement("SELECT action FROM audit_log WHERE workspace_id = ? ORDER BY seq").run {
                setObject(1, workspaceId)
                executeQuery().run { generateSequence { if (next()) getString(1) else null }.toList() }
            }
        }

    @Test
    fun `a technician joins by invitation, appears as staff, but can't sign in to the web app`() {
        val ownerClient = client()
        accounts.enrolled("chefin", ownerClient, workspaceName = "Brandt Haustechnik")
        val tech = technician(ownerClient)

        val login = client().post("/api/v1/auth/login", mapOf("email" to tech.email, "password" to tech.password))
        assertEquals(403, login.status, "$login")
        assertEquals("TECHNICIAN_USE_APP", login.code)

        val members =
            ownerClient
                .get("/api/v1/members")
                .tree
                .path("items")
                .values()
        assertEquals("TECHNICIAN", members.single { it.path("email").asString() == tech.email }.path("role").asString())
        val staff =
            ownerClient
                .get("/api/v1/staff")
                .tree
                .path("items")
                .values()
        assertTrue(staff.any { it.path("userId").asString() == tech.userId.toString() && it.path("role").asString() == "TECHNICIAN" })
        assertTrue(
            ownerClient
                .get("/api/v1/auth/session")
                .tree
                .path("features")
                .path("mara")
                .asBoolean(),
        )
    }

    @Test
    fun `an existing user who accepts a technician invitation keeps their own web workspace`() {
        val ownerClient = client()
        accounts.enrolled("einladend", ownerClient, workspaceName = "Elektro Fischer")
        val otherClient = client()
        val other = accounts.enrolled("selbststaendig", otherClient, workspaceName = "Eigene Firma")

        ownerClient.post("/api/v1/invitations", mapOf("email" to other.email, "role" to "TECHNICIAN"), key())
        val token = Mails.verificationToken(jdbc, other.email)
        val accepted = otherClient.post("/api/v1/auth/invitation/accept", mapOf("token" to token))
        assertEquals(200, accepted.status, "$accepted")
        assertEquals(
            other.workspaceId.toString(),
            accepted.tree
                .path("workspace")
                .path("id")
                .asString(),
            "session stays in the own workspace",
        )
        assertEquals(1, accepted.tree.path("workspaces").size(), "the technician workspace is not offered for the web app")

        val techWorkspace =
            TestDatabase.asOwner {
                it.prepareStatement("SELECT workspace_id FROM membership WHERE user_id = ? AND role = 'TECHNICIAN'").run {
                    setObject(1, other.userId)
                    executeQuery().run {
                        next()
                        getObject(1, UUID::class.java)
                    }
                }
            }
        val switch = otherClient.post("/api/v1/auth/workspace", mapOf("workspaceId" to techWorkspace))
        assertEquals(403, switch.status, "$switch")
        assertEquals("TECHNICIAN_USE_APP", switch.code)
    }

    @Test
    fun `work orders are created with site and assignees, rescheduled, reassigned and audited`() {
        val ownerClient = client()
        val owner = accounts.enrolled("planung", ownerClient, workspaceName = "Planung GmbH")
        val tech1 = technician(ownerClient, "geselle")
        val tech2 = technician(ownerClient, "azubi")
        val customer = customer(ownerClient)
        val site =
            ownerClient
                .post(
                    "/api/v1/sites",
                    mapOf(
                        "customerId" to customer,
                        "name" to "Wohnung 3.2",
                        "street" to "Lindenauer Markt 9",
                        "postalCode" to "04177",
                        "city" to "Leipzig",
                        "countryCode" to "DE",
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()
        val template =
            ownerClient
                .post(
                    "/api/v1/checklist-templates",
                    mapOf(
                        "name" to "Wartung Gasheizung",
                        "items" to
                            listOf(
                                mapOf("key" to "abgas", "label" to "Abgasmessung", "kind" to "YES_NO", "required" to true),
                                mapOf("key" to "co", "label" to "CO-Wert", "kind" to "VALUE", "required" to false),
                            ),
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()

        val created =
            ownerClient.post(
                "/api/v1/work-orders",
                mapOf(
                    "customerId" to customer,
                    "siteId" to site,
                    "title" to "Badsanierung",
                    "plannedFrom" to "2026-12-02T08:00:00+01:00",
                    "plannedTo" to "2026-12-02T12:00:00+01:00",
                    "assigneeIds" to listOf(tech1.userId, owner.userId),
                    "checklistTemplateId" to template,
                ),
                key(),
            )
        assertEquals(201, created.status, "$created")
        val id = created.tree.path("id").asString()
        assertEquals("PLANNED", created.tree.path("status").asString())
        assertEquals("Wohnbau Petersen GmbH", created.tree.path("customerName").asString())
        assertEquals("Lindenauer Markt 9, 04177 Leipzig", created.tree.path("address").asString())
        assertEquals(
            setOf(tech1.userId.toString(), owner.userId.toString()),
            created.tree
                .path("assigneeIds")
                .values()
                .map {
                    it.asString()
                }.toSet(),
        )

        // Calendar: the day's range finds it, the next day doesn't; the assignee filter works.
        fun day(
            from: String,
            to: String,
            extra: String = "",
        ) = ownerClient
            .get("/api/v1/work-orders?from=${enc(from)}&to=${enc(to)}$extra")
            .tree
            .path("items")
            .values()
            .map { it.path("id").asString() }
        assertTrue(id in day("2026-12-02T00:00:00+01:00", "2026-12-03T00:00:00+01:00"))
        assertFalse(id in day("2026-12-03T00:00:00+01:00", "2026-12-04T00:00:00+01:00"))
        assertTrue(id in day("2026-12-02T00:00:00+01:00", "2026-12-03T00:00:00+01:00", "&assigneeId=${tech1.userId}"))
        assertFalse(id in day("2026-12-02T00:00:00+01:00", "2026-12-03T00:00:00+01:00", "&assigneeId=${tech2.userId}"))

        // Reassign (the calendar's drag) and start the work.
        val etag = created.header("ETag")!!
        val moved =
            ownerClient.put(
                "/api/v1/work-orders/$id",
                mapOf(
                    "customerId" to customer,
                    "siteId" to site,
                    "title" to "Badsanierung",
                    "plannedFrom" to "2026-12-02T08:00:00+01:00",
                    "plannedTo" to "2026-12-02T12:00:00+01:00",
                    "assigneeIds" to listOf(tech2.userId, owner.userId),
                    "checklistTemplateId" to template,
                    "status" to "IN_PROGRESS",
                ),
                "If-Match" to etag,
            )
        assertEquals(200, moved.status, "$moved")
        assertEquals("IN_PROGRESS", moved.tree.path("status").asString())
        assertEquals(
            setOf(tech2.userId.toString(), owner.userId.toString()),
            moved.tree
                .path("assigneeIds")
                .values()
                .map { it.asString() }
                .toSet(),
        )

        // The old ETag is outdated now.
        val stale =
            ownerClient.put(
                "/api/v1/work-orders/$id",
                mapOf(
                    "customerId" to customer,
                    "title" to "x",
                    "assigneeIds" to emptyList<String>(),
                ),
                "If-Match" to etag,
            )
        assertEquals(412, stale.status, "$stale")

        val actions = auditActions(owner.workspaceId)
        assertTrue(
            actions.containsAll(
                listOf(
                    "workorders.site.created",
                    "workorders.checklist_template.created",
                    "workorders.work_order.created",
                    "workorders.work_order.status_changed",
                    "workorders.work_order.assigned",
                    "workorders.work_order.changed",
                ),
            ),
            "$actions",
        )
    }

    @Test
    fun `work orders check customers, sites, people, times and statuses`() {
        val ownerClient = client()
        val owner = accounts.enrolled("pruefung", ownerClient, workspaceName = "Pruefung GmbH")
        val (advisor, _) = accounts.invited(ownerClient, "ADVISOR")
        val customerA = customer(ownerClient, "Kunde A")
        val customerB = customer(ownerClient, "Kunde B")
        val siteOfB =
            ownerClient
                .post("/api/v1/sites", mapOf("customerId" to customerB, "name" to "Halle B", "countryCode" to "DE"), key())
                .tree
                .path("id")
                .asString()

        fun create(vararg fields: Pair<String, Any?>) =
            ownerClient.post(
                "/api/v1/work-orders",
                mapOf("customerId" to customerA, "title" to "Auftrag", "assigneeIds" to emptyList<String>()) + fields,
                key(),
            )

        assertEquals("SITE_NOT_FOR_CUSTOMER", create("siteId" to siteOfB).code)
        assertEquals("CUSTOMER_NOT_FOUND", create("customerId" to UUID.randomUUID()).code)
        assertEquals("ASSIGNEE_INVALID", create("assigneeIds" to listOf(advisor.userId)).code, "tax advisors are never assigned")
        assertEquals("ASSIGNEE_INVALID", create("assigneeIds" to listOf(UUID.randomUUID())).code)
        assertEquals(
            "PLANNED_TIME_INVALID",
            create("plannedFrom" to "2026-12-02T12:00:00+01:00", "plannedTo" to "2026-12-02T08:00:00+01:00").code,
        )
        val signedOnCreate = create("status" to "SIGNED")
        assertEquals(409, signedOnCreate.status)
        assertEquals("STATUS_NOT_ALLOWED", signedOnCreate.code)

        val ok = create("assigneeIds" to listOf(owner.userId))
        assertEquals(201, ok.status, "$ok")
        val id = ok.tree.path("id").asString()
        // A signed work order (set by MARA in Stage 7) doesn't change any more.
        TestDatabase.update("UPDATE work_order SET status = 'SIGNED' WHERE id = ?::uuid", id)
        val current = ownerClient.get("/api/v1/work-orders/$id")
        val locked =
            ownerClient.put(
                "/api/v1/work-orders/$id",
                mapOf("customerId" to customerA, "title" to "Geändert", "assigneeIds" to emptyList<String>()),
                "If-Match" to current.header("ETag")!!,
            )
        assertEquals(409, locked.status, "$locked")
        assertEquals("WORK_ORDER_LOCKED", locked.code)

        val duplicateKeys =
            ownerClient.post(
                "/api/v1/checklist-templates",
                mapOf(
                    "name" to "Doppelt",
                    "items" to List(2) { mapOf("key" to "gleich", "label" to "Punkt $it", "kind" to "YES_NO", "required" to false) },
                ),
                key(),
            )
        assertEquals("CHECKLIST_KEYS_DUPLICATE", duplicateKeys.code)

        val moveSite =
            ownerClient.put(
                "/api/v1/sites/$siteOfB",
                mapOf("customerId" to customerA, "name" to "Halle B", "countryCode" to "DE"),
                "If-Match" to "\"0\"",
            )
        assertEquals("SITE_CUSTOMER_FIXED", moveSite.code)
    }

    @Test
    fun `staff levels and staff settings belong to the owner`() {
        val ownerClient = client()
        accounts.enrolled("inhaber", ownerClient, workspaceName = "Stundensatz GmbH")
        val (_, memberClient) = accounts.invited(ownerClient, "MEMBER")
        val tech = technician(ownerClient)

        val byMember = memberClient.post("/api/v1/staff-levels", mapOf("name" to "Meister", "hourlyRate" to "75.00"), key())
        assertEquals(403, byMember.status, "office staff don't set prices: $byMember")

        val level = ownerClient.post("/api/v1/staff-levels", mapOf("name" to "Geselle", "hourlyRate" to "58.5"), key())
        assertEquals(201, level.status, "$level")
        assertEquals("58.50", level.tree.path("hourlyRate").asString())
        val levelId = level.tree.path("id").asString()

        val memberId =
            ownerClient
                .get("/api/v1/staff")
                .tree
                .path("items")
                .values()
                .single { it.path("userId").asString() == tech.userId.toString() }
                .path("memberId")
                .asString()
        val set =
            ownerClient.put(
                "/api/v1/staff/$memberId",
                mapOf("staffLevelId" to levelId, "canSeePrices" to true),
                "If-Match" to "\"0\"",
            )
        assertEquals(200, set.status, "$set")
        assertEquals(levelId, set.tree.path("staffLevelId").asString())
        assertTrue(set.tree.path("canSeePrices").asBoolean())
        assertEquals(403, memberClient.put("/api/v1/staff/$memberId", mapOf("canSeePrices" to false), "If-Match" to "\"0\"").status)

        // An archived level can't be given to anyone.
        ownerClient.put(
            "/api/v1/staff-levels/$levelId",
            mapOf("name" to "Geselle", "hourlyRate" to "58.50", "archived" to true),
            "If-Match" to level.header("ETag")!!,
        )
        assertEquals(
            "STAFF_LEVEL_NOT_FOUND",
            ownerClient
                .put(
                    "/api/v1/staff/$memberId",
                    mapOf("staffLevelId" to levelId, "canSeePrices" to false),
                    "If-Match" to "\"0\"",
                ).code,
        )
    }

    @Test
    fun `customers have a type and a Leitweg-ID, articles a purchase price`() {
        val c = client()
        accounts.enrolled("stammdaten", c)
        val customer =
            c.post(
                "/api/v1/customers",
                mapOf(
                    "name" to "Stadt Beispielhausen",
                    "countryCode" to "DE",
                    "vatTreatment" to "DOMESTIC",
                    "customerType" to "BUSINESS",
                    "leitwegId" to "14612000-0815-42",
                ),
                key(),
            )
        assertEquals(201, customer.status, "$customer")
        assertEquals("14612000-0815-42", customer.tree.path("leitwegId").asString())
        val privateCustomer =
            c.post(
                "/api/v1/customers",
                mapOf(
                    "name" to "Familie Keller",
                    "countryCode" to "DE",
                    "vatTreatment" to "DOMESTIC",
                    "customerType" to "PRIVATE",
                ),
                key(),
            )
        assertEquals("PRIVATE", privateCustomer.tree.path("customerType").asString())
        val defaulted = c.post("/api/v1/customers", mapOf("name" to "Ohne Typ", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"), key())
        assertEquals("BUSINESS", defaulted.tree.path("customerType").asString(), "existing clients send no type")
        val badLeitweg =
            c.post(
                "/api/v1/customers",
                mapOf(
                    "name" to "X",
                    "countryCode" to "DE",
                    "vatTreatment" to "DOMESTIC",
                    "leitwegId" to "keine ID",
                ),
                key(),
            )
        assertEquals(400, badLeitweg.status)

        val article =
            c.post(
                "/api/v1/articles",
                mapOf(
                    "name" to "Thermostatventil",
                    "unitCode" to "C62",
                    "netPrice" to "24.90",
                    "vatRate" to "STANDARD_19",
                    "purchasePrice" to "14.2",
                ),
                key(),
            )
        assertEquals(201, article.status, "$article")
        assertEquals("14.20", article.tree.path("purchasePrice").asString())
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)
}
