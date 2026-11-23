package de.erika.creator

import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.model.InvoiceFormat
import de.erika.support.ApiIntegrationTest
import de.erika.support.CreatorSupport
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestInfrastructure
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.Properties
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 8 through the real API and worker (Flow C): drafts with server-side checks (CR-01),
 * master data (CR-05), issuing with numbering and validation (CR-02, CR-03), credit notes and
 * cancellations (CR-06), sending with the archived email (CR-07), delivery webhooks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreatorIT : ApiIntegrationTest() {
    private lateinit var client: TestClient
    private lateinit var user: TestUser
    private lateinit var c: CreatorSupport
    private val year = LocalDate.now(Creator.BERLIN).year

    @BeforeAll
    fun account() {
        client = client()
        user = accounts.enrolled("creator", client)
        c = CreatorSupport(client, port)
        c.completeSeller()
    }

    @Test
    fun `an incomplete draft is saved but can't be issued (CR-01)`() {
        val draft = c.createDraft(mapOf("lines" to listOf(mapOf("name" to "Nur ein Name"))))
        val id = draft.tree.path("id").asString()
        assertEquals("DRAFT", draft.tree.path("state").asString())
        assertEquals(
            "ZUGFERD_EN16931",
            draft.tree
                .path("content")
                .path("format")
                .asString(),
            "default format from the settings",
        )
        val fields =
            draft.tree
                .path("problems")
                .values()
                .map { it.path("field").asString() }
        assertTrue(
            listOf("buyer.name", "buyer.city", "deliveryDate", "lines[0].quantity", "lines[0].netPrice").all { it in fields },
            "$fields",
        )

        val issue = client.post("/api/v1/invoices/$id/issue", null, c.key())
        assertEquals(422, issue.status, issue.toString())
        assertEquals("DRAFT_INCOMPLETE", issue.code)
        assertTrue(issue.tree.path("problems").size() > 3)
        assertEquals(
            "DRAFT",
            client
                .get("/api/v1/invoices/$id")
                .tree
                .path("state")
                .asString(),
        )

        // XRechnung additionally needs the buyer reference and the buyer's email (BR-DE-15, PEPPOL-EN16931-R010).
        val xr = c.content(format = "XRECHNUNG_CII").toMutableMap()
        xr.remove("buyerReference")
        val saved = c.save(id, xr)
        assertEquals(200, saved.status, saved.toString())
        assertEquals(
            listOf("buyerReference" to "REQUIRED_FOR_XRECHNUNG"),
            saved.tree.path("problems").values().map {
                it.path("field").asString() to
                    it.path("code").asString()
            },
        )

        // Optimistic locking on drafts.
        val stale = client.put("/api/v1/invoices/$id", xr, "If-Match" to "\"0\"")
        assertEquals(412, stale.status)
        assertEquals(428, client.put("/api/v1/invoices/$id", xr).status)
        assertEquals(204, client.send("DELETE", "/api/v1/invoices/$id", null).status)
        assertEquals(404, client.get("/api/v1/invoices/$id").status)
    }

    @Test
    fun `totals follow EN 16931 while editing`() {
        val draft = c.createDraft(c.content())
        val totals = draft.tree.path("totals")
        // 8 × 55.00 = 440.00 at 19 % (83.60), 2 × 24.99 = 49.98 at 7 % (3.50)
        assertEquals("489.98", totals.path("netAmount").asString())
        assertEquals("87.10", totals.path("taxAmount").asString())
        assertEquals("577.08", totals.path("grossAmount").asString())
        assertEquals(listOf("440.00", "49.98"), totals.path("lineAmounts").values().map { it.asString() })
        assertEquals(listOf("19.00", "7.00"), totals.path("vat").values().map { it.path("rate").asString() })
        assertEquals(0, draft.tree.path("problems").size(), draft.toString())
    }

    @Test
    fun `issuing assigns the number, validates, archives and creates the outgoing document (CR-02, CR-03)`() {
        val issued = c.createAndIssue(c.content(format = "XRECHNUNG_UBL"))
        assertEquals("ISSUED", issued.tree.path("state").asString(), issued.toString())
        val info = issued.tree.path("issued")
        assertTrue(info.path("number").asString().matches(Regex("RE-$year-\\d{4}")), info.toString())
        assertEquals("ISSUED", info.path("status").asString())
        val documentId = info.path("documentId").asString()

        val doc = client.get("/api/v1/documents/$documentId").tree
        assertEquals("OUT", doc.path("direction").asString())
        assertEquals("CREATED", doc.path("receivedVia").asString())
        assertEquals("XRECHNUNG_UBL", doc.path("format").asString())
        assertEquals("DONE", doc.path("processing").asString())
        assertTrue(doc.path("validation").path("level").asString() in setOf("VALID", "WARNING"), doc.toString())
        assertEquals(info.path("number").asString(), doc.path("invoice").path("invoiceNumber").asString())
        assertEquals(true, doc.path("viewAvailable").asBoolean())
        assertEquals(
            "ISSUED",
            doc
                .path("history")
                .values()
                .first()
                .path("toStatus")
                .asString(),
        )

        val original = c.rawGet("/api/v1/documents/$documentId/original").body()
        val parsed = InvoiceParser.parse(original)
        assertEquals(InvoiceFormat.XRECHNUNG_UBL, parsed.format.format)
        assertEquals(info.path("number").asString(), parsed.invoice!!.number)
        assertEquals(0, BigDecimal("577.08").compareTo(parsed.invoice!!.totals.totalWithVat))

        // Frozen: no more edits or deletion (GoBD).
        val edit = c.save(issued.tree.path("id").asString(), c.content())
        assertEquals(409, edit.status)
        assertEquals("INVOICE_NOT_EDITABLE", edit.code)
        assertEquals(409, client.send("DELETE", "/api/v1/invoices/${issued.tree.path("id").asString()}", null).status)
        // Outgoing invoices accept only SENT, PAID, BOOKED by hand.
        assertEquals(400, client.send("PATCH", "/api/v1/documents/$documentId", mapOf("status" to "CHECKED")).status)
    }

    @Test
    fun `a file that fails validation is not issued and its number is not used (Flow C step 3)`() {
        val before =
            c
                .createAndIssue()
                .tree
                .path("issued")
                .path("number")
                .asString()
        // "ZZ9" passes the form's format check but is no UN/ECE Rec 20 code: the code-list rule rejects the file.
        val rejected = c.createAndIssue(c.content(unitCode = "ZZ9"))
        assertEquals("DRAFT", rejected.tree.path("state").asString(), rejected.toString())
        assertTrue(rejected.tree.path("issueError").size() > 0)
        assertTrue(
            rejected.tree
                .path("issueError")
                .values()
                .any { it.path("ruleId").asString().startsWith("BR-CL") },
            rejected.toString(),
        )
        val after =
            c
                .createAndIssue()
                .tree
                .path("issued")
                .path("number")
                .asString()
        assertEquals(before.takeLast(4).toInt() + 1, after.takeLast(4).toInt(), "no gap: $before → $after")
    }

    @Test
    fun `customers and articles with idempotency keys and optimistic locking (CR-05)`() {
        val key = c.key()
        val body =
            mapOf(
                "name" to "Idempotent GmbH",
                "customerNumber" to "K-${UUID.randomUUID().toString().take(6)}",
                "countryCode" to "DE",
                "vatTreatment" to "DOMESTIC",
            )
        val first = client.post("/api/v1/customers", body, key)
        val again = client.post("/api/v1/customers", body, key)
        assertEquals(201, first.status, first.toString())
        assertEquals(first.tree.path("id").asString(), again.tree.path("id").asString(), "same key → same customer")
        assertEquals(
            422,
            client.post("/api/v1/articles", mapOf("name" to "x", "unitCode" to "C62", "netPrice" to "1", "vatRate" to "ZERO"), key).status,
        )
        assertEquals(409, client.post("/api/v1/customers", body, c.key()).status, "customer number taken")

        val id = first.tree.path("id").asString()
        val renamed = client.put("/api/v1/customers/$id", body + ("name" to "Umbenannt GmbH"), "If-Match" to first.header("ETag")!!)
        assertEquals(200, renamed.status, renamed.toString())
        assertEquals(412, client.put("/api/v1/customers/$id", body, "If-Match" to first.header("ETag")!!).status)
        assertTrue(
            client
                .get("/api/v1/customers?q=umbenannt")
                .tree
                .path("items")
                .values()
                .any { it.path("id").asString() == id },
        )

        val article =
            client.post(
                "/api/v1/articles",
                mapOf(
                    "name" to "Malerarbeiten",
                    "unitCode" to "HUR",
                    "netPrice" to "55.5",
                    "vatRate" to "STANDARD_19",
                ),
                c.key(),
            )
        assertEquals(201, article.status, article.toString())
        assertEquals("55.50", article.tree.path("netPrice").asString())
    }

    @Test
    fun `sending archives the email together with the file and records the delivery (CR-07)`() {
        val issued = c.createAndIssue()
        val id = issued.tree.path("id").asString()
        val documentId =
            issued.tree
                .path("issued")
                .path("documentId")
                .asString()
        val to = "kunde-${UUID.randomUUID().toString().take(8)}@example.test"
        val send = client.post("/api/v1/invoices/$id/send", mapOf("to" to to), c.key())
        assertEquals(202, send.status, send.toString())
        assertEquals("QUEUED", send.tree.path("status").asString())
        WorkerContext.drain()

        val after = client.get("/api/v1/invoices/$id").tree
        val delivery = after.path("deliveries").values().single()
        assertEquals("SENT", delivery.path("status").asString())
        assertEquals(true, delivery.path("archived").asBoolean())
        assertEquals("SENT", after.path("issued").path("status").asString())

        // The archived message carries the invoice file byte for byte.
        val eml = c.rawGet("/api/v1/invoices/$id/deliveries/${delivery.path("id").asString()}/message")
        assertEquals(200, eml.statusCode())
        val message = MimeMessage(Session.getInstance(Properties()), eml.body().inputStream())
        assertEquals(to, message.allRecipients.single().toString())
        val parts = message.content as MimeMultipart
        val attachment = (0 until parts.count).map { parts.getBodyPart(it) }.single { it.fileName != null }
        val file = c.rawGet("/api/v1/documents/$documentId/original").body()
        assertContentEquals(file, attachment.inputStream.readBytes())
        assertEquals(
            issued.tree
                .path("issued")
                .path("fileName")
                .asString(),
            attachment.fileName,
        )

        // Mailpit received it.
        val search = URLEncoder.encode("to:$to", Charsets.UTF_8)
        val found =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${TestInfrastructure.mailpitApi}/api/v1/search?query=$search")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(
            1,
            JsonMapper
                .builder()
                .build()
                .readTree(found.body())
                .path("messages")
                .size(),
            found.body(),
        )

        // A bounce reported by the provider marks the document DELIVERY_FAILED.
        val messageId = message.getHeader("Message-ID").single()
        assertEquals(401, webhook(messageId, "BOUNCED", secret = "wrong").statusCode())
        assertEquals(202, webhook(messageId, "BOUNCED").statusCode())
        assertEquals(202, webhook("<unknown@x>", "BOUNCED").statusCode())
        val bounced = client.get("/api/v1/invoices/$id").tree
        assertEquals(
            "BOUNCED",
            bounced
                .path("deliveries")
                .values()
                .single()
                .path("status")
                .asString(),
        )
        assertEquals("DELIVERY_FAILED", bounced.path("issued").path("status").asString())
        val history =
            client
                .get("/api/v1/documents/$documentId")
                .tree
                .path("history")
                .values()
                .map { it.path("toStatus").asString() }
        assertEquals(listOf("ISSUED", "SENT", "DELIVERY_FAILED"), history)
        val audited =
            TestDatabase.asOwner { conn ->
                conn
                    .prepareStatement(
                        "SELECT count(*) FROM audit_log WHERE workspace_id = ? AND action IN ('creator.invoice.sent', 'creator.delivery.bounced')",
                    ).apply { setObject(1, user.workspaceId) }
                    .executeQuery()
                    .run {
                        next()
                        getInt(1)
                    }
            }
        assertTrue(audited >= 2)
    }

    @Test
    fun `a cancellation references the original and cancels it (CR-06)`() {
        val original = c.createAndIssue(c.content(format = "XRECHNUNG_CII"))
        val originalId = original.tree.path("id").asString()
        val originalNumber =
            original.tree
                .path("issued")
                .path("number")
                .asString()
        val draft = client.post("/api/v1/invoices/$originalId/credit-note", mapOf("kind" to "CANCELLATION"), c.key())
        assertEquals(201, draft.status, draft.toString())
        assertEquals("CANCELLATION", draft.tree.path("kind").asString())
        assertEquals(
            originalNumber,
            draft.tree
                .path("original")
                .path("number")
                .asString(),
        )
        assertEquals(
            2,
            draft.tree
                .path("content")
                .path("lines")
                .size(),
            "lines taken over",
        )

        val issued = c.issue(draft.tree.path("id").asString())
        assertEquals("ISSUED", issued.tree.path("state").asString(), issued.toString())
        assertTrue(
            issued.tree
                .path("issued")
                .path("number")
                .asString()
                .startsWith("GS-$year-"),
        )
        val file = c.rawGet("/api/v1/documents/${issued.tree.path("issued").path("documentId").asString()}/original").body()
        val parsed = InvoiceParser.parse(file).invoice!!
        assertEquals("381", parsed.typeCode)
        assertEquals(originalNumber, parsed.precedingInvoices.single().number)

        val originalAfter = client.get("/api/v1/invoices/$originalId").tree
        assertEquals("CANCELLED", originalAfter.path("issued").path("status").asString())
        assertEquals(1, originalAfter.path("corrections").size())
        val twice = client.post("/api/v1/invoices/$originalId/credit-note", mapOf("kind" to "CANCELLATION"), c.key())
        assertEquals(409, twice.status)
        assertEquals("ALREADY_CANCELLED", twice.code)
        val ofCredit =
            client.post(
                "/api/v1/invoices/${issued.tree.path("id").asString()}/credit-note",
                mapOf("kind" to "CREDIT_NOTE"),
                c.key(),
            )
        assertEquals(409, ofCredit.status)
    }

    @Test
    fun `invoices list shows drafts and issued invoices with their status`() {
        val issued = c.createAndIssue()
        val list = client.get("/api/v1/invoices?state=ISSUED&limit=100").tree.path("items")
        val item = list.single { it.path("id").asString() == issued.tree.path("id").asString() }
        assertNotNull(item.path("number").asString())
        assertEquals("ISSUED", item.path("status").asString())
        assertEquals("577.08", item.path("grossAmount").asString())
    }

    private fun webhook(
        messageId: String,
        event: String,
        secret: String = "test-webhook-secret",
    ): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/webhooks/mail"))
                .header("Content-Type", "application/json")
                .header("X-Erika-Webhook-Secret", secret)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                            mapOf(
                                "messageId" to messageId,
                                "event" to event,
                                "detail" to "550 mailbox unavailable",
                            ),
                        ),
                    ),
                ).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
