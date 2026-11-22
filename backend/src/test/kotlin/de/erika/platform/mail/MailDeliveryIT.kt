package de.erika.platform.mail

import de.erika.platform.jobs.JobWorker
import de.erika.support.TestInfrastructure
import de.erika.support.WorkerIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertEquals

/** Mail queued in a transaction is delivered by the worker over SMTP (Mailpit here). */
class MailDeliveryIT : WorkerIntegrationTest() {
    @Autowired lateinit var outbox: Outbox

    @Autowired lateinit var worker: JobWorker

    @Autowired lateinit var tx: TransactionTemplate

    @Test
    fun `queued mail is sent by the worker`() {
        val to = "delivery-${UUID.randomUUID()}@example.test"
        tx.executeWithoutResult { outbox.send(Mail(to, "Grüße aus dem Test", "Hallo,\nschöne Grüße.")) }
        worker.runOnce()

        val search = URLEncoder.encode("to:$to", Charsets.UTF_8)
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${TestInfrastructure.mailpitApi}/api/v1/search?query=$search")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        val messages =
            JsonMapper
                .builder()
                .build()
                .readTree(response.body())
                .path("messages")
        assertEquals(1, messages.size(), response.body())
        assertEquals("Grüße aus dem Test", messages.first().path("Subject").asString())
    }

    @Test
    fun `mail is not sent when the transaction rolls back`() {
        val to = "rollback-${UUID.randomUUID()}@example.test"
        runCatching {
            tx.executeWithoutResult {
                outbox.send(Mail(to, "Never", "Never"))
                error("roll back")
            }
        }
        worker.runOnce()
        val search = URLEncoder.encode("to:$to", Charsets.UTF_8)
        val body =
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create("${TestInfrastructure.mailpitApi}/api/v1/search?query=$search")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body()
        assertEquals(
            0,
            JsonMapper
                .builder()
                .build()
                .readTree(body)
                .path("messages")
                .size(),
        )
    }
}
