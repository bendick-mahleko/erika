package de.erika.intake

import de.erika.support.ApiIntegrationTest
import de.erika.support.Samples
import de.erika.support.TestClient
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** IN-06: with the allow-list on, email from unknown senders waits in quarantine until released. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllowListIT : ApiIntegrationTest() {
    private lateinit var user: TestUser
    private lateinit var client: TestClient
    private lateinit var address: String
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeAll
    fun account() {
        client = client()
        user = accounts.enrolled("allowlist", client)
        address =
            client
                .get("/api/v1/intake/address")
                .tree
                .path("address")
                .asString()
    }

    @Test
    fun `senders are normalised, deduplicated and invalid patterns refused`() {
        val first = addSender(" <Rechnung@Partner.example> ")
        assertEquals(201, first.status, first.toString())
        assertEquals("rechnung@partner.example", first.tree.path("pattern").asString())
        assertEquals(first.tree.path("id").asString(), addSender("rechnung@partner.example").tree.path("id").asString())
        assertEquals("SENDER_PATTERN_INVALID", addSender("kein muster").code)
        val id = first.tree.path("id").asString()
        assertEquals(204, client.send("DELETE", "/api/v1/intake/senders/$id", null).status)
        assertEquals(404, client.send("DELETE", "/api/v1/intake/senders/$id", null).status)
    }

    @Test
    fun `mail from an unknown sender is quarantined, released and then imported`() {
        assertEquals(200, client.put("/api/v1/intake/settings", mapOf("allowListEnabled" to true)).status)
        assertEquals(201, addSender("@bekannt.example").status)
        try {
            val known = "KNOWN-${UUID.randomUUID()}"
            val unknown = "UNKNOWN-${UUID.randomUUID()}"
            postMail(known, "buchhaltung@bekannt.example")
            postMail(unknown, "fremd@unbekannt.example")
            WorkerContext.drain()

            assertTrue(fileNames().contains("$known.xml"), "a known sender's invoice is imported")
            assertTrue(!fileNames().contains("$unknown.xml"), "an unknown sender's invoice waits")
            val held =
                client
                    .get("/api/v1/intake/quarantine")
                    .tree
                    .path("items")
                    .single { it.path("fromAddress").asString().contains("fremd@unbekannt.example") }
            assertEquals("Ihre Rechnung", held.path("subject").asString())

            val id = held.path("id").asString()
            assertEquals(202, client.post("/api/v1/intake/quarantine/$id/release", mapOf("allowSender" to true)).status)
            assertEquals(404, client.post("/api/v1/intake/quarantine/$id/release", mapOf("allowSender" to false)).status)
            WorkerContext.drain()
            assertTrue(fileNames().contains("$unknown.xml"), "released mail is imported")
            val patterns =
                client
                    .get("/api/v1/intake/settings")
                    .tree
                    .path("senders")
                    .values()
                    .map { it.path("pattern").asString() }
            assertTrue("fremd@unbekannt.example" in patterns, patterns.toString())
        } finally {
            client.put("/api/v1/intake/settings", mapOf("allowListEnabled" to false))
        }
    }

    @Test
    fun `discarded mail is not imported`() {
        assertEquals(200, client.put("/api/v1/intake/settings", mapOf("allowListEnabled" to true)).status)
        try {
            val invoice = "DISCARD-${UUID.randomUUID()}"
            postMail(invoice, "spam@werbung.example")
            WorkerContext.drain()
            val id =
                client
                    .get("/api/v1/intake/quarantine")
                    .tree
                    .path("items")
                    .single { it.path("fromAddress").asString().contains("spam@werbung.example") }
                    .path("id")
                    .asString()
            assertEquals(204, client.post("/api/v1/intake/quarantine/$id/discard", null).status)
            assertTrue(
                client
                    .get("/api/v1/intake/quarantine")
                    .tree
                    .path("items")
                    .none { it.path("id").asString() == id },
            )
            WorkerContext.drain()
            assertTrue(!fileNames().contains("$invoice.xml"))
        } finally {
            client.put("/api/v1/intake/settings", mapOf("allowListEnabled" to false))
        }
    }

    private fun addSender(pattern: String) =
        client.post("/api/v1/intake/senders", mapOf("pattern" to pattern), "Idempotency-Key" to UUID.randomUUID().toString())

    private fun fileNames(): List<String> =
        client
            .get("/api/v1/documents?limit=100")
            .tree
            .path("items")
            .values()
            .map { it.path("fileName").asString() }

    private fun postMail(
        number: String,
        from: String,
    ) {
        val raw = Samples.email(address, Triple("$number.xml", "application/xml", Samples.xrechnung(number)), from = from)
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/intake/mail"))
                .header("Content-Type", "message/rfc822")
                .header("X-Erika-Recipient", address)
                .header("X-Erika-Sender", from)
                .header("X-Intake-Secret", "test-intake-secret")
                .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
                .build()
        assertEquals(202, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
    }
}
