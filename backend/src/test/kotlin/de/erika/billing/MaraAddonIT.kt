package de.erika.billing

import de.erika.support.ApiIntegrationTest
import de.erika.support.FakeStripe
import de.erika.support.Phones
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestSyncDevice
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MARA Stage 10 (M-AC-02, M-BR-09, ADR 0043): the add-on's trial, booking on the subscription,
 * seats charged above three users (also when someone joins or leaves mid-period, prorated), the
 * read-only state and exempt beta firms, through the API, the worker and signed webhooks against
 * the fake Stripe API. The run against Stripe's test mode is the manual check in docs/ops/billing.md.
 */
class MaraAddonIT : ApiIntegrationTest() {
    private val signature = StripeSignature("whsec_test_erika")
    private val phones by lazy { Phones(this) }

    private fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    private fun owner(prefix: String): Pair<TestUser, TestClient> {
        val client = client()
        return accounts.enrolled(prefix, client, workspaceName = "MARA Kunde $prefix", maraTrial = false) to client
    }

    private fun addon(client: TestClient) = client.get("/api/v1/billing/addons/mara").tree

    private fun state(client: TestClient) = addon(client).path("state").asString()

    /** A MARA change any owner or member may make: a checklist template. */
    private fun createTemplate(client: TestClient) =
        client.post(
            "/api/v1/checklist-templates",
            mapOf(
                "name" to "Wartung ${UUID.randomUUID().toString().take(4)}",
                "items" to listOf(mapOf("key" to "druck", "label" to "Druck", "kind" to "VALUE", "required" to false)),
            ),
            key(),
        )

    @Test
    fun `without the add-on MARA can be read but not used, the trial needs the works-council notice and runs once`() {
        val (user, client) = owner("addon-trial")
        assertEquals("NONE", state(client))
        assertEquals(200, client.get("/api/v1/work-orders").status, "reading always works")
        val refused = createTemplate(client)
        assertEquals(403, refused.status, refused.toString())
        assertEquals("MARA_NOT_BOOKED", refused.code)

        val (member, memberClient) = accounts.invited(client, "MEMBER", "addon-member")
        assertEquals(403, memberClient.post("/api/v1/billing/addons/mara/trial", mapOf("worksCouncilNoticeAccepted" to true)).status)
        val noNotice = client.post("/api/v1/billing/addons/mara/trial", mapOf("worksCouncilNoticeAccepted" to false))
        assertEquals(400, noNotice.status)
        assertEquals("WORKS_COUNCIL_NOTICE_REQUIRED", noNotice.code)

        val trial = client.post("/api/v1/billing/addons/mara/trial", mapOf("worksCouncilNoticeAccepted" to true))
        assertEquals(200, trial.status, trial.toString())
        assertEquals("TRIAL", trial.tree.path("state").asString())
        assertTrue(trial.tree.path("worksCouncilNoticeAccepted").asBoolean())
        val ends = Instant.parse(trial.tree.path("trialEndsAt").asString())
        assertTrue(ends.isAfter(Instant.now().plusSeconds(29L * 86400)) && ends.isBefore(Instant.now().plusSeconds(31L * 86400)))
        assertEquals(
            2,
            trial.tree
                .path("seats")
                .path("counted")
                .asInt(),
            "owner and member count",
        )
        assertEquals(
            3,
            trial.tree
                .path("seats")
                .path("included")
                .asInt(),
        )
        assertEquals(
            "39.00",
            trial.tree
                .path("price")
                .path("netAmount")
                .asString(),
        )
        assertEquals(
            "9.00",
            trial.tree
                .path("price")
                .path("seatNetAmount")
                .asString(),
        )
        assertEquals(201, createTemplate(client).status)
        assertEquals(201, createTemplate(memberClient).status)

        val again = client.post("/api/v1/billing/addons/mara/trial", mapOf("worksCouncilNoticeAccepted" to true))
        assertEquals(409, again.status)
        assertEquals("MARA_TRIAL_USED", again.code)
        assertTrue("billing.mara.trial_started" in audit(user.workspaceId))
        check(member.workspaceId == user.workspaceId)
    }

    @Test
    fun `after the trial MARA is read-only - office and phones read, nothing new is saved or synced, devices can still be revoked`() {
        val (user, client) = owner("addon-readonly")
        assertEquals(200, client.post("/api/v1/billing/addons/mara/trial", mapOf("worksCouncilNoticeAccepted" to true)).status)
        val (phone, _) = phones.signIn(user)
        val device = TestSyncDevice(phone)
        device.pullAll()
        TestDatabase.update("UPDATE billing_addon SET trial_ends_at = now() - interval '1 minute' WHERE workspace_id = ?", user.workspaceId)

        assertEquals("READ_ONLY", state(client))
        assertEquals(200, client.get("/api/v1/work-orders").status)
        val refused = createTemplate(client)
        assertEquals(403, refused.status)
        assertEquals("MARA_READ_ONLY", refused.code)

        val mutation =
            mapOf(
                "mutationId" to UUID.randomUUID().toString(),
                "entity" to "customer",
                "entityId" to UUID.randomUUID().toString(),
                "op" to "CREATE",
                "values" to mapOf("name" to "Neukunde vom Telefon", "countryCode" to "DE"),
            )
        val push = phone.post("/api/v1/mobile/sync/push", mapOf("protocolVersion" to 1, "mutations" to listOf(mutation)))
        assertEquals(403, push.status, push.toString())
        assertEquals("MARA_READ_ONLY", push.code)
        assertEquals(200, phone.get("/api/v1/mobile/sync/pull?protocolVersion=1").status, "the phone can still pull")
        assertEquals(202, client.post("/api/v1/exports", mapOf("kind" to "FULL"), key()).status, "export stays possible")

        val devices =
            client
                .get("/api/v1/devices")
                .tree
                .path("items")
                .values()
                .toList()
        val revoke = client.post("/api/v1/devices/${devices.first().path("id").asString()}/revoke", null)
        assertEquals(200, revoke.status, "a lost phone can always be locked out: $revoke")
    }

    @Test
    fun `booked on the subscription - seats above three are charged and follow people joining and leaving, prorated`() {
        val (user, client) = owner("addon-seats")
        val customer = subscribe(client, user, "sub_mara_${UUID.randomUUID().toString().take(8)}")
        val subscription =
            TestDatabase
                .strings(
                    "SELECT subscription_id FROM billing_account WHERE workspace_id = ?",
                    user.workspaceId,
                ).single()
        assertEquals("NONE", state(client))
        val noNotice = client.post("/api/v1/billing/addons/mara/subscription", mapOf("worksCouncilNoticeAccepted" to false), key())
        assertEquals(400, noNotice.status)

        FakeStripe.requests.clear()
        val booked = client.post("/api/v1/billing/addons/mara/subscription", mapOf("worksCouncilNoticeAccepted" to true), key())
        assertEquals(202, booked.status, booked.toString())
        assertEquals("BOOKING", booked.tree.path("pending").asString())
        WorkerContext.drain()
        val added = FakeStripe.requestsTo("/v1/subscription_items").single()
        assertEquals("price_mara_monthly", added.params["price"])
        assertEquals(subscription, added.params["subscription"])
        assertEquals("create_prorations", added.params["proration_behavior"])
        assertTrue(added.headers.keys.any { it.equals("Idempotency-Key", ignoreCase = true) })
        val maraItem = itemId(user, "item_id")
        subscriptionEvent(user, customer, subscription, mara = maraItem)
        WorkerContext.drain()
        assertEquals("ACTIVE", state(client))
        assertEquals(201, createTemplate(client).status)

        // Three more people: 4 seats, 1 extra → a seat item with quantity 1.
        val tech1 = phones.technician(client, "addon-t1")
        phones.technician(client, "addon-t2")
        WorkerContext.drain()
        assertEquals(3, addon(client).path("seats").path("counted").asInt())
        assertTrue(FakeStripe.requests.none { it.path == "/v1/subscription_items" && it.params["price"] == "price_mara_seat_monthly" })
        accounts.invited(client, "MEMBER", "addon-m1")
        WorkerContext.drain()
        val seatCreated =
            FakeStripe.requests.single {
                it.path == "/v1/subscription_items" &&
                    it.params["price"] == "price_mara_seat_monthly"
            }
        assertEquals("1", seatCreated.params["quantity"])
        assertEquals("create_prorations", seatCreated.params["proration_behavior"])
        val seatItem = itemId(user, "seat_item_id")
        assertEquals(1, addon(client).path("seats").path("billed").asInt())

        // Mid-month: one more joins (quantity 2), then a technician leaves (back to 1); tax advisors are free.
        accounts.invited(client, "MEMBER", "addon-m2")
        accounts.invited(client, "ADVISOR", "addon-advisor")
        WorkerContext.drain()
        assertEquals(listOf("2"), FakeStripe.requestsTo("/v1/subscription_items/$seatItem").map { it.params["quantity"] })
        val techMember =
            client.get("/api/v1/members").tree.path("items").values().single {
                it.path("userId").asString() ==
                    tech1.userId.toString()
            }
        assertEquals(204, client.send("DELETE", "/api/v1/members/${techMember.path("id").asString()}", null).status)
        WorkerContext.drain()
        assertEquals(listOf("2", "1"), FakeStripe.requestsTo("/v1/subscription_items/$seatItem").map { it.params["quantity"] })
        assertTrue(FakeStripe.requestsTo("/v1/subscription_items/$seatItem").all { it.params["proration_behavior"] == "create_prorations" })
        assertEquals(4, addon(client).path("seats").path("counted").asInt(), "owner, technician, two members; the advisor is free")

        // Cancelled: both items removed, MARA read-only.
        val cancelled = client.send("DELETE", "/api/v1/billing/addons/mara/subscription", null)
        assertEquals(202, cancelled.status, cancelled.toString())
        WorkerContext.drain()
        val deleted = FakeStripe.requests.filter { it.method == "DELETE" && it.path.startsWith("/v1/subscription_items/") }.map { it.path }
        assertEquals(setOf("/v1/subscription_items/$seatItem", "/v1/subscription_items/$maraItem"), deleted.toSet())
        assertEquals("READ_ONLY", state(client))
        assertTrue(audit(user.workspaceId).containsAll(listOf("billing.mara.book", "billing.mara.cancel")))
    }

    @Test
    fun `MARA booked with the plan in the checkout, with the seats above three`() {
        val (user, client) = owner("addon-checkout")
        phones.technician(client, "addon-c1")
        phones.technician(client, "addon-c2")
        phones.technician(client, "addon-c3")
        FakeStripe.requests.clear()
        val noNotice = client.post("/api/v1/billing/checkout", mapOf("plan" to "ANNUAL", "addons" to listOf("MARA")), key())
        assertEquals(400, noNotice.status, noNotice.toString())
        val checkout =
            client.post(
                "/api/v1/billing/checkout",
                mapOf("plan" to "ANNUAL", "addons" to listOf("MARA"), "worksCouncilNoticeAccepted" to true),
                key(),
            )
        assertEquals(200, checkout.status, checkout.toString())
        val session = FakeStripe.requestsTo("/v1/checkout/sessions").last().params
        assertEquals("price_annual", session["line_items[0][price]"])
        assertEquals("price_mara_annual", session["line_items[1][price]"])
        assertEquals("1", session["line_items[1][quantity]"])
        assertEquals("price_mara_seat_annual", session["line_items[2][price]"])
        assertEquals("1", session["line_items[2][quantity]"], "4 people, 3 included")
        assertTrue(addon(client).path("worksCouncilNoticeAccepted").asBoolean())
        check(user.workspaceId != UUID(0, 0))
    }

    @Test
    fun `beta firms are exempt - MARA free without Stripe`() {
        val (user, client) = owner("addon-beta")
        TestDatabase.update("INSERT INTO billing_addon (workspace_id, addon, exempt) VALUES (?, 'MARA', true)", user.workspaceId)
        assertEquals("EXEMPT", state(client))
        assertEquals(201, createTemplate(client).status)
    }

    // --- helpers ------------------------------------------------------------------------

    private fun itemId(
        user: TestUser,
        column: String,
    ) = TestDatabase.strings("SELECT $column FROM billing_addon WHERE workspace_id = ?", user.workspaceId).single()

    /** A checkout and an active subscription (monthly) by webhook; returns the Stripe customer. */
    private fun subscribe(
        client: TestClient,
        user: TestUser,
        subscription: String,
    ): String {
        assertEquals(200, client.post("/api/v1/billing/checkout", mapOf("plan" to "MONTHLY"), key()).status)
        val customer =
            TestDatabase
                .strings(
                    "SELECT stripe_customer_id FROM billing_account WHERE workspace_id = ?",
                    user.workspaceId,
                ).single()
        subscriptionEvent(user, customer, subscription)
        WorkerContext.drain()
        return customer
    }

    private fun subscriptionEvent(
        user: TestUser,
        customer: String,
        subscription: String,
        mara: String? = null,
    ) {
        val created = Instant.now().epochSecond
        val maraItem =
            mara?.let {
                """,{"id":"$it","quantity":1,"current_period_end":${created + 30 * 86400},
                    "price":{"id":"price_mara_monthly","recurring":{"interval":"month"}}}"""
            } ?: ""
        val event =
            """
            {"id":"evt_${UUID.randomUUID().toString().replace("-", "")}","type":"customer.subscription.updated","created":$created,
             "data":{"object":{"id":"$subscription","object":"subscription","customer":"$customer","status":"active",
               "cancel_at_period_end":false,"metadata":{"workspace_id":"${user.workspaceId}"},
               "items":{"data":[{"id":"si_base","quantity":1,"current_period_end":${created + 30 * 86400},
                                 "price":{"id":"price_monthly","recurring":{"interval":"month"}}}$maraItem]}}}}
            """.trimIndent()
        assertEquals(
            200,
            client().send("POST", "/webhooks/stripe", event, arrayOf("Stripe-Signature" to signature.header(event)), csrf = false).status,
        )
    }

    private fun audit(workspaceId: UUID): List<String> =
        TestDatabase.strings("SELECT action FROM audit_log WHERE workspace_id = ?", workspaceId)
}
