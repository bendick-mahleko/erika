package de.erika.intake

import de.erika.platform.jobs.JobQueueMetrics
import de.erika.support.ApiIntegrationTest
import de.erika.support.Mails
import de.erika.support.Response
import de.erika.support.Samples
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 5 exit criteria (IN-01, IN-02, IN-04, IN-05, NF-04), through both runtime roles:
 * the API answers the browser's requests, the file goes to object storage with the
 * pre-signed URL, and the worker ([WorkerContext]) scans, unpacks, archives and validates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntakeIT : ApiIntegrationTest() {
    private lateinit var user: TestUser
    private lateinit var client: TestClient
    private val http: HttpClient = HttpClient.newHttpClient()

    @Autowired lateinit var queueMetrics: JobQueueMetrics

    @Autowired lateinit var meters: MeterRegistry

    @BeforeAll
    fun account() {
        client = client()
        user = accounts.enrolled("intake", client)
    }

    @Test
    fun `an uploaded XRechnung is scanned, archived, validated and appears in the inbox`() {
        val upload = upload("rechnung-1.xml", Samples.xrechnung("UP-${UUID.randomUUID()}"))
        assertEquals("DONE", upload.tree.path("status").asString(), upload.toString())
        val item = upload.tree.path("items").single()
        assertEquals("IMPORTED", item.path("outcome").asString())
        val documentId = item.path("documentId").asString()

        val document = client.get("/api/v1/documents/$documentId")
        assertEquals(200, document.status, document.toString())
        with(document.tree) {
            assertEquals("XRECHNUNG_UBL", path("format").asString())
            assertEquals("DONE", path("processing").asString())
            assertEquals("INVOICE", path("docType").asString())
            assertEquals("NEW", path("status").asString())
            assertEquals("UPLOAD", path("receivedVia").asString())
            assertEquals("rechnung-1.xml", path("fileName").asString())
            assertEquals("336.9", path("invoice").path("grossAmount").asString())
            assertEquals("DE123456789", path("invoice").path("sellerTaxId").asString())
            assertTrue(path("validation").path("level").asString() in setOf("VALID", "WARNING"), toString())
            assertEquals("xrechnung-3.0.2_2026-08-31", path("validation").path("packId").asString())
        }
        val list = client.get("/api/v1/documents")
        assertTrue(list.tree.path("items").any { it.path("id").asString() == documentId }, list.toString())
        assertTrue(
            client
                .get("/api/v1/inbox/summary")
                .tree
                .path("newDocuments")
                .asInt() >= 1,
        )
    }

    @Test
    fun `a ZIP with 10 files gives 10 inbox items (IN-02)`() {
        val run = UUID.randomUUID().toString().take(8)
        val entries = (1..10).map { "rechnungen/re-$it.xml" to Samples.xrechnung("ZIP-$run-$it") }.toTypedArray()
        val upload = upload("rechnungen.zip", Samples.zip(*entries), "application/zip")
        assertEquals("DONE", upload.tree.path("status").asString(), upload.toString())
        val items = upload.tree.path("items").toList()
        assertEquals(10, items.size)
        assertTrue(items.all { it.path("outcome").asString() == "IMPORTED" }, upload.toString())
        assertEquals("rechnungen.zip/rechnungen/re-1.xml", items.first().path("fileName").asString())
        val ids = items.map { it.path("documentId").asString() }.toSet()
        assertEquals(10, ids.size)
        ids.forEach {
            assertEquals(
                "XRECHNUNG_UBL",
                client
                    .get("/api/v1/documents/$it")
                    .tree
                    .path("format")
                    .asString(),
            )
        }
    }

    @Test
    fun `re-uploading the same file shows possible duplicate (IN-05)`() {
        val bytes = Samples.xrechnung("DUP-${UUID.randomUUID()}")
        val first = upload("rechnung.xml", bytes).tree.path("items").single()
        val second = upload("rechnung-nochmal.xml", bytes).tree.path("items").single()
        assertEquals("ALREADY_IN_ARCHIVE", second.path("outcome").asString())
        assertEquals(first.path("documentId").asString(), second.path("documentId").asString())
    }

    @Test
    fun `the same invoice in another file is flagged as a business duplicate (IN-05)`() {
        val number = "BIZ-${UUID.randomUUID()}"
        val first =
            upload("a.xml", Samples.xrechnung(number))
                .tree
                .path("items")
                .single()
                .path("documentId")
                .asString()
        val second = upload("b.xml", Samples.xrechnungResent(number)).tree.path("items").single()
        assertEquals("IMPORTED", second.path("outcome").asString())
        val document = client.get("/api/v1/documents/${second.path("documentId").asString()}").tree
        assertEquals(first, document.path("duplicateOf").asString())
    }

    @Test
    fun `an EICAR test file is rejected and not archived (NF-04)`() {
        val upload = upload("rechnung.xml", Samples.eicar())
        val item = upload.tree.path("items").single()
        assertEquals("REJECTED", item.path("outcome").asString(), upload.toString())
        assertEquals("VIRUS_FOUND", item.path("reason").asString())
        assertTrue(item.path("documentId").isMissingNode)
        val audited =
            jdbcAsOwner("SELECT count(*) FROM audit_log WHERE workspace_id = ? AND action = 'intake.virus.detected'", user.workspaceId)
        assertTrue(audited >= 1)
    }

    @Test
    fun `a zip bomb is rejected as a whole (NF-04)`() {
        val upload = upload("bombe.zip", Samples.zipBomb(), "application/zip")
        assertEquals("REJECTED", upload.tree.path("status").asString(), upload.toString())
        assertEquals("ZIP_BOMB", upload.tree.path("reason").asString())
    }

    @Test
    fun `a plain PDF is labelled not an e-invoice and still archived (IN-04)`() {
        val upload = upload("scan.pdf", Samples.plainPdf(), "application/pdf")
        val documentId =
            upload.tree
                .path("items")
                .single()
                .path("documentId")
                .asString()
        val document = client.get("/api/v1/documents/$documentId").tree
        assertEquals("PDF_ONLY", document.path("format").asString())
        assertEquals("PLAIN_PDF", document.path("notEInvoiceReason").asString())
        assertEquals("OTHER", document.path("docType").asString())
        assertEquals(false, document.path("validation").path("applicable").asBoolean())
        val archived =
            jdbcAsOwner(
                "SELECT count(*) FROM stored_object WHERE workspace_id = ? AND sha256 = ?",
                user.workspaceId,
                document.path("sha256").asString(),
            )
        assertEquals(1, archived)
    }

    @Test
    fun `unsupported files in a ZIP are refused one by one, the rest is imported`() {
        val entries =
            arrayOf(
                "re.xml" to Samples.xrechnung("MIX-${UUID.randomUUID()}"),
                "foto.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3),
                "leer.pdf" to ByteArray(0),
                ".DS_Store" to byteArrayOf(1, 2, 3),
            )
        val items = upload("gemischt.zip", Samples.zip(*entries), "application/zip").tree.path("items").toList()
        val outcomes =
            items.associate {
                it.path("fileName").asString() to
                    (it.path("outcome").asString() + ":" + it.path("reason").asString())
            }
        assertEquals(
            mapOf(
                "gemischt.zip/re.xml" to "IMPORTED:",
                "gemischt.zip/foto.jpg" to "REJECTED:UNSUPPORTED_FILE_TYPE",
                "gemischt.zip/leer.pdf" to "REJECTED:EMPTY_FILE",
            ),
            outcomes,
        )
    }

    @Test
    fun `complete before the file arrived is refused, a larger file than announced can't be sent`() {
        val started = startUpload("klein.xml", 10)
        val id = started.tree.path("id").asString()
        val early = client.post("/api/v1/uploads/$id/complete")
        assertEquals(409, early.status)
        assertEquals("UPLOAD_FILE_MISSING", early.code)
        // The signature covers the length: storage refuses a different size.
        val put = putToStorage(started, ByteArray(5000) { 'x'.code.toByte() })
        assertTrue(put >= 400, "storage accepted a file larger than announced: $put")
        assertEquals(409, client.post("/api/v1/uploads/$id/complete").status)
    }

    @Test
    fun `an upload of another workspace is not found`() {
        val other = accounts.enrolled("intake-other")
        val otherClient = accounts.signIn(other)
        val id = startUpload("x.xml", 10, otherClient).tree.path("id").asString()
        assertEquals(404, client.get("/api/v1/uploads/$id").status)
        assertEquals(404, client.post("/api/v1/uploads/$id/complete").status)
    }

    @Test
    fun `an email with an XRechnung attachment appears in the inbox within 2 minutes (IN-01)`() {
        val address =
            client
                .get("/api/v1/intake/address")
                .tree
                .path("address")
                .asString()
        assertTrue(address.endsWith("@in.erika.local"), address)
        assertEquals(200, client.put("/api/v1/notification-settings", mapOf("emailOnNewDocuments" to true)).status)

        val started = System.nanoTime()
        val xml = Samples.xrechnung("MAIL-${UUID.randomUUID()}")
        val mail =
            Samples.email(
                address,
                Triple("rechnung.xml", "application/xml", xml),
                Triple("scan.pdf", "application/pdf", Samples.plainPdf()),
            )
        assertEquals(202, postMail(address, mail).statusCode())
        WorkerContext.drain()
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertTrue(elapsed < Duration.ofMinutes(2), "took $elapsed")

        val emailed =
            client
                .get("/api/v1/documents?limit=100")
                .tree
                .path("items")
                .filter { it.path("receivedVia").asString() == "EMAIL" }
        val xmlDoc = emailed.single { it.path("fileName").asString() == "rechnung.xml" }
        assertEquals("XRECHNUNG_UBL", xmlDoc.path("format").asString())
        assertEquals("DONE", xmlDoc.path("processing").asString())
        assertTrue(emailed.any { it.path("format").asString() == "PDF_ONLY" })
        // The raw email is archived write-once too.
        assertEquals(
            1,
            jdbcAsOwner(
                "SELECT count(*) FROM stored_object WHERE workspace_id = ? AND media_type = 'message/rfc822' AND sha256 = encode(sha256(?), 'hex')",
                user.workspaceId,
                mail,
            ),
        )
        // The member who asked for it gets one notification email.
        val notifications = Mails.queuedTo(jdbc, user.email).filter { it["subject"] == "Neue Dokumente in ERIKA" }
        assertEquals(1, notifications.size)
        assertTrue(notifications.single()["body"]!!.contains("2 neue Dokumente sind per E-Mail"), notifications.toString())
    }

    @Test
    fun `mail intake needs the secret and a known recipient`() {
        val address =
            client
                .get("/api/v1/intake/address")
                .tree
                .path("address")
                .asString()
        val mail = Samples.email(address, Triple("r.xml", "application/xml", Samples.xrechnung("SEC-1")))
        assertEquals(401, postMail(address, mail, secret = null).statusCode())
        assertEquals(401, postMail(address, mail, secret = "wrong").statusCode())
        assertEquals(404, postMail("unbekannt1234567890ab@in.erika.local", mail).statusCode())
        assertEquals(404, postMail(address.replace("in.erika.local", "example.com"), mail).statusCode())

        assertEquals("true", checkRecipient(address.uppercase()))
        assertEquals("true", checkRecipient(address.replace("@", "+rechnungen@")))
        assertEquals("false", checkRecipient("unbekannt1234567890ab@in.erika.local"))
    }

    @Test
    fun `an email without attachments is recorded and imports nothing`() {
        val address =
            client
                .get("/api/v1/intake/address")
                .tree
                .path("address")
                .asString()
        val before =
            client
                .get("/api/v1/documents?limit=100")
                .tree
                .path("items")
                .size()
        assertEquals(202, postMail(address, Samples.email(address)).statusCode())
        WorkerContext.drain()
        assertEquals(
            before,
            client
                .get("/api/v1/documents?limit=100")
                .tree
                .path("items")
                .size(),
        )
        val noAttachments =
            jdbcAsOwner("SELECT count(*) FROM intake WHERE workspace_id = ? AND reason = 'NO_ATTACHMENTS'", user.workspaceId)
        assertTrue(noAttachments >= 1)
    }

    @Test
    fun `a job whose worker died mid-run is taken over and produces exactly one result`() {
        // Queue the upload, then pretend a worker claimed its job and died (lease expired).
        val bytes = Samples.xrechnung("CRASH-${UUID.randomUUID()}")
        val started = startUpload("crash.xml", bytes.size.toLong())
        assertEquals(200, putToStorage(started, bytes))
        val id = started.tree.path("id").asString()
        assertEquals(202, client.post("/api/v1/uploads/$id/complete").status)
        TestDatabase.update(
            """
            UPDATE job SET state = 'RUNNING', attempts = 1, locked_by = 'worker-that-died', locked_until = now() - interval '1 second'
             WHERE type = 'intake.process-upload' AND payload->>'intakeId' = ?
            """.trimIndent(),
            id,
        )
        WorkerContext.drain()
        val documentId =
            client
                .get("/api/v1/uploads/$id")
                .tree
                .path("items")
                .single()
                .path("documentId")
                .asString()
        assertEquals(1, jdbcAsOwner("SELECT count(*) FROM intake_item WHERE intake_id = ?::uuid", id))
        assertEquals(1, jdbcAsOwner("SELECT count(*) FROM validation_result WHERE document_id = ?::uuid", documentId))

        // The same for the validation job: a died worker's claim is taken over once.
        TestDatabase.update(
            """
            UPDATE job SET state = 'RUNNING', attempts = 1, locked_by = 'worker-that-died', locked_until = now() - interval '1 second',
                           finished_at = NULL
             WHERE type = 'documents.parse-and-validate' AND payload->>'documentId' = ?
            """.trimIndent(),
            documentId,
        )
        WorkerContext.drain()
        assertEquals(1, jdbcAsOwner("SELECT count(*) FROM validation_result WHERE document_id = ?::uuid", documentId))
        assertEquals(
            1,
            jdbcAsOwner(
                "SELECT count(*) FROM document WHERE workspace_id = ? AND sha256 = encode(sha256(?), 'hex')",
                user.workspaceId,
                bytes,
            ),
        )
    }

    @Test
    fun `the API publishes job queue gauges for the intake alerts`() {
        // A due intake job nobody has run yet (no worker drains in between).
        val started = startUpload("alert.xml", 4)
        assertEquals(200, putToStorage(started, "<a/>".toByteArray()))
        assertEquals(202, client.post("/api/v1/uploads/${started.tree.path("id").asString()}/complete").status)
        queueMetrics.refresh()
        val age = meters.find("erika.jobs.oldest.due").tag("type", "intake.process-upload").gauge()
        assertNotNull(age, "gauge for queued intake jobs")
        assertTrue(age.value() >= 0)
        WorkerContext.drain()
        queueMetrics.refresh()
        assertNull(meters.find("erika.jobs.oldest.due").tag("type", "intake.process-upload").gauge())
    }

    // --- helpers ------------------------------------------------------------------------

    private fun startUpload(
        name: String,
        size: Long,
        as_: TestClient = client,
    ): Response {
        val started = as_.post("/api/v1/uploads", mapOf("fileName" to name, "size" to size, "contentType" to "application/octet-stream"))
        assertEquals(201, started.status, started.toString())
        assertEquals("AWAITING_FILE", started.tree.path("status").asString())
        assertNotNull(started.tree.path("uploadUrl").asString())
        return started
    }

    /** Sends the file with the pre-signed URL and the returned headers, as the browser does. */
    private fun putToStorage(
        started: Response,
        bytes: ByteArray,
    ): Int {
        val request = HttpRequest.newBuilder(URI.create(started.tree.path("uploadUrl").asString()))
        started.tree
            .path("uploadHeaders")
            .properties()
            .forEach { (name, value) -> request.header(name, value.asString()) }
        return http
            .send(
                request.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()
    }

    private fun upload(
        name: String,
        bytes: ByteArray,
        contentType: String = "application/xml",
    ): Response {
        val started = client.post("/api/v1/uploads", mapOf("fileName" to name, "size" to bytes.size, "contentType" to contentType))
        assertEquals(201, started.status, started.toString())
        assertEquals(200, putToStorage(started, bytes))
        val id = started.tree.path("id").asString()
        val completed = client.post("/api/v1/uploads/$id/complete")
        assertEquals(202, completed.status, completed.toString())
        assertEquals("RECEIVED", completed.tree.path("status").asString())
        WorkerContext.drain()
        val result = client.get("/api/v1/uploads/$id")
        assertNull(result.tree.path("uploadUrl").asString(null))
        return result
    }

    private fun postMail(
        recipient: String,
        raw: ByteArray,
        secret: String? = "test-intake-secret",
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/intake/mail"))
                .header("Content-Type", "message/rfc822")
                .header("X-Erika-Recipient", recipient)
                .header("X-Erika-Sender", "rechnung@lieferant.example")
        secret?.let { request.header("X-Intake-Secret", it) }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun checkRecipient(recipient: String): String {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/intake/recipients/check"))
                .header("Content-Type", "application/json")
                .header("X-Intake-Secret", "test-intake-secret")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(mapOf("recipient" to recipient))))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body()).path("known").asString()
    }

    private fun jdbcAsOwner(
        sql: String,
        vararg args: Any,
    ): Long =
        TestDatabase.asOwner { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }
}
