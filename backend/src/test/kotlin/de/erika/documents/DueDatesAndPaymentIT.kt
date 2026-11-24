package de.erika.documents

import de.erika.platform.jobs.JobQueue
import de.erika.support.ApiIntegrationTest
import de.erika.support.Mails
import de.erika.support.Samples
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Release 1.1 for received invoices: English viewer (VW-07), due-date reminders (AR-05), GiroCode (AR-06). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DueDatesAndPaymentIT : ApiIntegrationTest() {
    private lateinit var user: TestUser
    private lateinit var client: TestClient
    private val http: HttpClient = HttpClient.newHttpClient()

    @Autowired lateinit var jobs: JobQueue

    @Autowired lateinit var tx: TransactionTemplate

    @BeforeAll
    fun account() {
        client = client()
        user = accounts.enrolled("due-dates", client)
    }

    @Test
    fun `the viewer answers in English on request and falls back to German otherwise (VW-07)`() {
        val id = uploadDocument(Samples.xrechnung("EN-${UUID.randomUUID()}"))
        val english = client.get("/api/v1/documents/$id/view?lang=en")
        assertEquals(200, english.status, english.toString())
        assertEquals("en", english.tree.path("language").asString())
        val fields = fieldsOf(english.tree)
        assertEquals("Seller name", fields.first { it.first == "BT-27" }.second)
        assertEquals("2016-04-04", fields.first { it.first == "BT-2" }.third)
        assertEquals("336.90 EUR", fields.first { it.first == "BT-112" }.third)

        val german = client.get("/api/v1/documents/$id/view").tree
        assertEquals("de", german.path("language").asString())
        assertEquals("Name des Verkäufers", fieldsOf(german).first { it.first == "BT-27" }.second)
    }

    @Test
    fun `received invoices carry the transfer details and an EPC QR payload (AR-06)`() {
        val number = "PAY-${UUID.randomUUID()}".take(30)
        val id = uploadDocument(Samples.xrechnung(number))
        val transfer = client.get("/api/v1/documents/$id").tree.path("transfer")
        assertEquals("DE79000000001234567890", transfer.path("iban").asString())
        assertEquals("[Seller name]", transfer.path("name").asString())
        assertEquals("336.90", transfer.path("amount").asString())
        assertEquals(number, transfer.path("reference").asString())
        assertEquals(
            listOf("BCD", "002", "1", "SCT", "", "[Seller name]", "DE79000000001234567890", "EUR336.90", "", "", number),
            transfer.path("epcPayload").asString().split("\n"),
        )
    }

    @Test
    fun `members get one reminder 3 days before invoices are due, paid ones are skipped (AR-05)`() {
        val due = LocalDate.now().plusDays(30)
        val open = "DUE-${UUID.randomUUID()}".take(30)
        val paid = "PAID-${UUID.randomUUID()}".take(30)
        uploadDocument(withDueDate(open, due))
        val paidId = uploadDocument(withDueDate(paid, due))
        TestDatabase.update("UPDATE document SET status = 'PAID' WHERE id = ?", UUID.fromString(paidId))

        runReminders(due)
        val reminders = Mails.queuedTo(jdbc, user.email).filter { it["subject"] == "Fällige Rechnungen in ERIKA" }
        assertEquals(1, reminders.size, reminders.toString())
        val body = reminders.single()["body"]!!
        assertTrue(body.contains(open), body)
        assertTrue(!body.contains(paid), body)
        assertTrue(
            body.contains(
                due.format(
                    java.time.format.DateTimeFormatter
                        .ofPattern("dd.MM.yyyy"),
                ),
            ),
            body,
        )

        // A second run (or a retried job) sends nothing twice.
        runReminders(due)
        assertEquals(1, Mails.queuedTo(jdbc, user.email).count { it["subject"] == "Fällige Rechnungen in ERIKA" })

        // Switched off: no reminder for the next due invoice.
        val off = client.put("/api/v1/notification-settings", mapOf("emailOnNewDocuments" to false, "emailOnDueDates" to false))
        assertEquals(false, off.tree.path("emailOnDueDates").asBoolean())
        val later = due.plusDays(1)
        uploadDocument(withDueDate("LATER-${UUID.randomUUID()}".take(30), later))
        runReminders(later)
        assertEquals(1, Mails.queuedTo(jdbc, user.email).count { it["subject"] == "Fällige Rechnungen in ERIKA" })
        client.put("/api/v1/notification-settings", mapOf("emailOnNewDocuments" to false, "emailOnDueDates" to true))
    }

    private fun runReminders(due: LocalDate) {
        tx.execute { jobs.enqueue(DueReminderJobHandler.TYPE, DueReminderJobHandler.Payload(due), maxAttempts = 1) }
        WorkerContext.drain()
    }

    /** The official sample with a due date (BT-9). */
    private fun withDueDate(
        number: String,
        due: LocalDate,
    ): ByteArray =
        Samples
            .xrechnung(number)
            .decodeToString()
            .replaceFirst("<cbc:InvoiceTypeCode>", "<cbc:DueDate>$due</cbc:DueDate><cbc:InvoiceTypeCode>")
            .toByteArray()

    private fun fieldsOf(view: tools.jackson.databind.JsonNode): List<Triple<String, String, String>> =
        view.path("sections").toList().flatMap(::sectionFields)

    private fun sectionFields(section: tools.jackson.databind.JsonNode): List<Triple<String, String, String>> =
        section.path("fields").toList().map { Triple(it.path("bt").asString(), it.path("label").asString(), it.path("value").asString()) } +
            section.path("sections").toList().flatMap(::sectionFields)

    private fun uploadDocument(bytes: ByteArray): String {
        val started =
            client.post(
                "/api/v1/uploads",
                mapOf(
                    "fileName" to "rechnung.xml",
                    "size" to bytes.size,
                    "contentType" to "application/xml",
                ),
            )
        assertEquals(201, started.status, started.toString())
        val put = HttpRequest.newBuilder(URI.create(started.tree.path("uploadUrl").asString()))
        started.tree
            .path("uploadHeaders")
            .properties()
            .forEach { (k, v) -> put.header(k, v.asString()) }
        http.send(put.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding())
        val id = started.tree.path("id").asString()
        assertEquals(202, client.post("/api/v1/uploads/$id/complete").status)
        WorkerContext.drain()
        return client
            .get("/api/v1/uploads/$id")
            .tree
            .path("items")
            .single()
            .path("documentId")
            .asString()
    }
}
