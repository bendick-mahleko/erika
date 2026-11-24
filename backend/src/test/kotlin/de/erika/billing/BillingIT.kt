package de.erika.billing

import de.erika.support.ApiIntegrationTest
import de.erika.support.FakeStripe
import de.erika.support.Mails
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestInfrastructure
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 10 through the API and the worker, with signed Stripe webhooks and a fake Stripe API
 * (the run against Stripe's test clock is the manual check in docs/ops/billing.md):
 * trial → checkout → subscription active → invoice paid → ERIKA's own e-invoice issued
 * (validated, so it passes VW-03), emailed and listed; failed payment → dunning → read-only,
 * all audited; closing the account cancels the subscription.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BillingIT : ApiIntegrationTest() {
    private val signature = StripeSignature("whsec_test_erika")
    private val issuer = UUID.fromString("00000000-0000-4000-8000-0000000e41ca")
    private val key get() = "Idempotency-Key" to UUID.randomUUID().toString()

    @BeforeAll
    fun erikaWorkspace() {
        // ERIKA's own workspace issues the invoices: complete company data and invoice settings.
        TestDatabase.update(
            """
            INSERT INTO workspace (id, name, street, postal_code, city, country_code, vat_id, tax_number)
            VALUES (?, 'ERIKA Software GmbH', 'Softwareweg 1', '10115', 'Berlin', 'DE', 'DE987654321', '30/987/65432')
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            issuer,
        )
        TestDatabase.update(
            """
            INSERT INTO invoice_settings (workspace_id, contact_name, contact_phone, contact_email, iban, bic, number_pattern)
            VALUES (?, 'Buchhaltung ERIKA', '+49 30 7654321', 'rechnung@erika.example', 'DE02120300000000202051', 'BYLADEM1001',
                    'ERIKA-{YYYY}-{NNNNN}')
            ON CONFLICT (workspace_id) DO NOTHING
            """.trimIndent(),
            issuer,
        )
    }

    @Test
    fun `a new workspace is in its trial, and owners start Stripe Checkout with the rest of the trial and German VAT`() {
        val (owner, client) = customer("trial")
        val status = client.get("/api/v1/billing").tree
        assertEquals("TRIAL", status.path("state").asString())
        val trialEnds = Instant.parse(status.path("trialEndsAt").asString())
        assertTrue(Duration.between(Instant.now(), trialEnds) > Duration.ofDays(13))
        val monthly = status.path("plans").values().single { it.path("plan").asString() == "MONTHLY" }
        assertEquals("29.00", monthly.path("netAmount").asString())
        assertEquals("EUR", monthly.path("currency").asString())

        val before = FakeStripe.requests.size
        val checkout = client.post("/api/v1/billing/checkout", mapOf("plan" to "MONTHLY"), key)
        assertEquals(200, checkout.status, checkout.toString())
        assertTrue(
            checkout.tree
                .path("url")
                .asString()
                .startsWith("https://checkout.stripe.test/"),
        )
        val calls = FakeStripe.requests.drop(before)
        val customer = calls.single { it.path == "/v1/customers" }
        assertEquals(owner.workspaceId.toString(), customer.params["metadata[workspace_id]"])
        assertEquals("Stripe Kunde trial GmbH", customer.params["name"])
        val session = calls.single { it.path == "/v1/checkout/sessions" }
        assertEquals("subscription", session.params["mode"])
        assertEquals("price_monthly", session.params["line_items[0][price]"])
        assertEquals("sepa_debit", session.params["payment_method_types[1]"])
        assertEquals("txr_test_19", session.params["subscription_data[default_tax_rates][0]"])
        assertEquals(trialEnds.epochSecond.toString(), session.params["subscription_data[trial_end]"])
        assertNotNull(session.headers["Idempotency-key"] ?: session.headers["Idempotency-Key"])
        assertTrue(
            client
                .get("/api/v1/billing")
                .tree
                .path("hasCustomer")
                .asBoolean(),
        )
        assertEquals(200, client.post("/api/v1/billing/portal", null, key).status)
        assertTrue("billing.checkout.started" in audit(owner.workspaceId))

        val (_, member) = accounts.invited(client, "MEMBER")
        assertEquals(403, member.post("/api/v1/billing/checkout", mapOf("plan" to "MONTHLY"), key).status, "owners only")
    }

    @Test
    fun `after the trial without a subscription the workspace is read-only, but can still read, export and pay`() {
        val (owner, client) = customer("expired")
        TestDatabase.update("UPDATE billing_account SET trial_ends_at = now() - interval '1 day' WHERE workspace_id = ?", owner.workspaceId)
        assertEquals(
            "READ_ONLY",
            client
                .get("/api/v1/billing")
                .tree
                .path("state")
                .asString(),
        )
        val write = client.post("/api/v1/customers", mapOf("name" to "X", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"), key)
        assertEquals(403, write.status)
        assertEquals("SUBSCRIPTION_REQUIRED", write.code)
        assertEquals(200, client.get("/api/v1/documents").status)
        assertEquals(202, client.post("/api/v1/exports", mapOf("kind" to "FULL"), key).status, "the archive stays accessible")
        assertEquals(200, client.post("/api/v1/billing/checkout", mapOf("plan" to "ANNUAL"), key).status, "paying is possible")
        val session = FakeStripe.requestsTo("/v1/checkout/sessions").last()
        assertEquals(null, session.params["subscription_data[trial_end]"], "no trial left to keep")
    }

    @Test
    fun `trial ends, the payment is collected and ERIKA's own e-invoice is issued, validated and sent (EX-03)`() {
        val (owner, client) = customer("paying")
        val cus = checkoutCustomer(client, owner)
        assertEquals(400, webhook("""{"id":"evt_x","type":"invoice.paid","created":1,"data":{"object":{}}}""", "t=1,v1=00").status)

        subscriptionEvent(owner, cus, "sub_paying", "active")
        WorkerContext.drain()
        assertEquals(
            "ACTIVE",
            client
                .get("/api/v1/billing")
                .tree
                .path("state")
                .asString(),
        )

        val email = "buchhaltung-${UUID.randomUUID().toString().take(6)}@example.test"
        val paidAt = Instant.now().epochSecond
        val stripeInvoice = "in_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val invoice =
            """
            {"id":"evt_$stripeInvoice","type":"invoice.paid","created":$paidAt,"data":{"object":{
              "id":"$stripeInvoice","object":"invoice","customer":"$cus","customer_email":"$email","currency":"eur",
              "amount_paid":3451,"total":3451,"total_excluding_tax":2900,"subtotal":2900,"number":"STRIPE-0001",
              "status_transitions":{"paid_at":$paidAt},
              "lines":{"data":[{"description":"1 × ERIKA Monatlich","quantity":1,"amount":2900,
                                "period":{"start":$paidAt,"end":${paidAt + 30 * 86400}}}]}}}}
            """.trimIndent()
        assertEquals(200, webhook(invoice).status)
        assertEquals(200, webhook(invoice).status, "a repeated delivery is acknowledged")
        WorkerContext.drain()

        val listed =
            client
                .get("/api/v1/billing/invoices")
                .tree
                .path("items")
                .values()
                .single()
        assertEquals("ISSUED", listed.path("state").asString(), "issued means it passed validation (VW-03)")
        assertTrue(listed.path("number").asString().matches(Regex("^ERIKA-\\d{4}-\\d{5}$")), listed.toString())
        assertEquals(listOf("29.00", "5.51", "34.51"), listOf("netAmount", "taxAmount", "grossAmount").map { listed.path(it).asString() })

        // The invoice in ERIKA's workspace: one per Stripe invoice, issued with a valid validation result.
        val issued =
            TestDatabase.asOwner {
                it
                    .prepareStatement(
                        """
                        SELECT i.number, i.gross, v.level FROM billing_invoice b
                          JOIN invoice i ON i.id = b.erika_invoice_id
                          JOIN validation_result v ON v.document_id = i.document_id
                         WHERE b.stripe_invoice_id = ?
                        """.trimIndent(),
                    ).run {
                        setString(1, stripeInvoice)
                        executeQuery().run {
                            generateSequence {
                                if (next()) {
                                    Triple(
                                        getString(1),
                                        getBigDecimal(2).toPlainString(),
                                        getString(3),
                                    )
                                } else {
                                    null
                                }
                            }.toList()
                        }
                    }
            }
        assertEquals(1, issued.map { it.first }.distinct().size)
        assertEquals("34.51", issued.first().second)
        assertTrue(issued.all { it.third != "ERROR" }, "$issued")

        // Emailed to the billing address (and to the customer's intake address, into their inbox).
        val recipients = deliveries(stripeInvoice)
        assertTrue(email in recipients, "$recipients")
        assertTrue(recipients.any { it.endsWith("@in.erika.local") }, "$recipients")
        val mails = mailpitMessagesTo(email)
        assertTrue(mails > 0, "the invoice email reached the customer")
        assertTrue(audit(owner.workspaceId).containsAll(listOf("billing.subscription.changed", "billing.payment.succeeded")))
        assertTrue("billing.own_invoice.requested" in audit(issuer))
    }

    @Test
    fun `a failed SEPA payment leads through dunning to read-only, all audited`() {
        val (owner, client) = customer("dunning")
        val cus = checkoutCustomer(client, owner)
        subscriptionEvent(owner, cus, "sub_dunning", "active")
        WorkerContext.drain()

        val failed =
            """
            {"id":"evt_fail_${UUID.randomUUID()}","type":"invoice.payment_failed","created":${Instant.now().epochSecond},
             "data":{"object":{"id":"in_failed","object":"invoice","customer":"$cus","attempt_count":1}}}
            """.trimIndent()
        assertEquals(200, webhook(failed).status)
        subscriptionEvent(owner, cus, "sub_dunning", "past_due", offset = 10)
        WorkerContext.drain()
        assertEquals(
            "PAST_DUE",
            client
                .get("/api/v1/billing")
                .tree
                .path("state")
                .asString(),
        )
        assertEquals(
            201,
            client
                .post(
                    "/api/v1/customers",
                    mapOf(
                        "name" to "Noch erlaubt",
                        "countryCode" to "DE",
                        "vatTreatment" to "DOMESTIC",
                    ),
                    key,
                ).status,
        )
        assertTrue(Mails.queuedTo(jdbc, owner.email).any { it["subject"]!!.contains("fehlgeschlagen") }, "owners are told")

        subscriptionEvent(owner, cus, "sub_dunning", "unpaid", offset = 20)
        // An older event arriving late doesn't undo the newer state.
        subscriptionEvent(owner, cus, "sub_dunning", "active", offset = 5)
        WorkerContext.drain()
        assertEquals(
            "READ_ONLY",
            client
                .get("/api/v1/billing")
                .tree
                .path("state")
                .asString(),
        )
        assertEquals("SUBSCRIPTION_REQUIRED", client.post("/api/v1/customers", mapOf("name" to "X"), key).code)

        val log = audit(owner.workspaceId)
        assertTrue("billing.payment.failed" in log)
        val changes =
            TestDatabase.asOwner {
                it
                    .prepareStatement(
                        "SELECT payload->>'to' FROM audit_log WHERE workspace_id = ? AND action = 'billing.subscription.changed' ORDER BY seq",
                    ).run {
                        setObject(1, owner.workspaceId)
                        executeQuery().run { generateSequence { if (next()) getString(1) else null }.toList() }
                    }
            }
        assertEquals(listOf("active", "past_due", "unpaid"), changes)
    }

    @Test
    fun `closing the account cancels the Stripe subscription`() {
        val (owner, client) = customer("closing")
        val cus = checkoutCustomer(client, owner)
        subscriptionEvent(owner, cus, "sub_closing", "active")
        WorkerContext.drain()
        assertEquals(200, client.post("/api/v1/workspace/closure", mapOf("confirmName" to "Stripe Kunde closing GmbH")).status)
        WorkerContext.drain()
        assertTrue(FakeStripe.requests.any { it.method == "DELETE" && it.path == "/v1/subscriptions/sub_closing" })
        assertTrue("billing.subscription.cancel_requested" in audit(owner.workspaceId))
    }

    // --- helpers ------------------------------------------------------------------------

    private fun customer(prefix: String): Pair<TestUser, TestClient> {
        val client = client()
        val user = accounts.enrolled(prefix, client, workspaceName = "Stripe Kunde $prefix GmbH")
        val ws = client.get("/api/v1/workspace")
        client.put(
            "/api/v1/workspace",
            mapOf(
                "name" to "Stripe Kunde $prefix GmbH",
                "street" to "Kundenweg 9",
                "postalCode" to "80331",
                "city" to "München",
                "countryCode" to "DE",
                "vatId" to "DE111222333",
            ),
            "If-Match" to ws.header("ETag")!!,
        )
        return user to client
    }

    /** Runs a checkout so the workspace has a Stripe customer; returns its ID. */
    private fun checkoutCustomer(
        client: TestClient,
        user: TestUser,
    ): String {
        assertEquals(200, client.post("/api/v1/billing/checkout", mapOf("plan" to "MONTHLY"), key).status)
        return TestDatabase.asOwner {
            it.prepareStatement("SELECT stripe_customer_id FROM billing_account WHERE workspace_id = ?").run {
                setObject(1, user.workspaceId)
                executeQuery().run {
                    next()
                    getString(1)
                }
            }
        }
    }

    private fun subscriptionEvent(
        user: TestUser,
        customer: String,
        subscription: String,
        status: String,
        offset: Long = 0,
    ) {
        val created = Instant.now().epochSecond + offset
        val event =
            """
            {"id":"evt_${UUID.randomUUID().toString().replace("-", "")}","type":"customer.subscription.updated","created":$created,
             "data":{"object":{"id":"$subscription","object":"subscription","customer":"$customer","status":"$status",
               "cancel_at_period_end":false,"metadata":{"workspace_id":"${user.workspaceId}"},
               "items":{"data":[{"current_period_end":${created + 30 * 86400},
                                 "price":{"id":"price_monthly","recurring":{"interval":"month"}}}]}}}}
            """.trimIndent()
        assertEquals(200, webhook(event).status)
    }

    private fun webhook(
        payload: String,
        header: String = signature.header(payload),
    ) = client().send("POST", "/webhooks/stripe", payload, arrayOf("Stripe-Signature" to header), csrf = false)

    private fun deliveries(stripeInvoice: String): List<String> =
        TestDatabase.asOwner {
            it
                .prepareStatement(
                    "SELECT d.recipient FROM invoice_delivery d JOIN billing_invoice b ON b.erika_invoice_id = d.invoice_id WHERE b.stripe_invoice_id = ?",
                ).run {
                    setString(1, stripeInvoice)
                    executeQuery().run { generateSequence { if (next()) getString(1) else null }.toList() }
                }
        }

    private fun mailpitMessagesTo(email: String): Int {
        val query = URLEncoder.encode("to:$email", Charsets.UTF_8)
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${TestInfrastructure.mailpitApi}/api/v1/search?query=$query")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return json.readTree(response.body()).path("messages").size()
    }

    private fun audit(workspaceId: UUID): List<String> =
        TestDatabase.asOwner {
            it.prepareStatement("SELECT action FROM audit_log WHERE workspace_id = ? ORDER BY seq").run {
                setObject(1, workspaceId)
                executeQuery().run { generateSequence { if (next()) getString(1) else null }.toList() }
            }
        }
}
