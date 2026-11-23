package de.erika.documents

import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.view.ViewRenderer
import de.erika.support.ApiIntegrationTest
import de.erika.support.Response
import de.erika.support.Samples
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 6 (VW-01, VW-02, VW-06, AR-01, AR-03, AR-04, NF-07) through both runtime roles:
 * files are uploaded through the API and read by the worker; the viewer data, downloads,
 * status history and search are then read back through the API.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentsIT : ApiIntegrationTest() {
    private lateinit var user: TestUser
    private lateinit var client: TestClient
    private val http: HttpClient = HttpClient.newHttpClient()
    private val fixtures = File(System.getProperty("erika.test-fixtures"))

    @BeforeAll
    fun account() {
        client = client()
        user = accounts.enrolled("documents", client)
    }

    @Test
    fun `the viewer shows every business term with a German label, and the PDF view copy is marked`() {
        val id = uploadDocument("rechnung.xml", Samples.xrechnung("VIEW-${UUID.randomUUID()}"))
        val document = client.get("/api/v1/documents/$id").tree
        assertEquals(true, document.path("viewAvailable").asBoolean())

        val view = client.get("/api/v1/documents/$id/view")
        assertEquals(200, view.status, view.toString())
        val fields =
            view.tree
                .path("sections")
                .toList()
                .flatMap { fieldsOf(it) }
        val byBt = fields.associate { it.first to it.third }
        assertEquals("Name des Verkäufers", fields.first { it.first == "BT-27" }.second)
        assertEquals("336,90 EUR", byBt["BT-112"])
        assertEquals("04.04.2016", byBt["BT-2"])

        val pdf = rawGet("/api/v1/documents/$id/view-pdf")
        assertEquals(200, pdf.statusCode())
        assertEquals("application/pdf", pdf.headers().firstValue("Content-Type").get())
        assertTrue(
            pdf
                .headers()
                .firstValue("Content-Disposition")
                .get()
                .startsWith("attachment"),
        )
        val text = Loader.loadPDF(pdf.body()).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains(ViewRenderer.DEFAULT_MARKING), "PDF carries the marking")
        assertTrue(text.contains("336,90 EUR"))
    }

    @Test
    fun `the original downloads unchanged with its SHA-256 digest (AR-01)`() {
        val bytes = Samples.xrechnung("ORIG-${UUID.randomUUID()}")
        val id = uploadDocument("original.xml", bytes)
        val response = rawGet("/api/v1/documents/$id/original")
        assertEquals(200, response.statusCode())
        assertContentEquals(bytes, response.body())
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        assertEquals("sha-256=:$digest:", response.headers().firstValue("Repr-Digest").get())
        assertEquals("sha-256=$digest", response.headers().firstValue("Digest").get())
        assertTrue(
            response
                .headers()
                .firstValue("Content-Disposition")
                .get()
                .contains("original.xml"),
        )
    }

    @Test
    fun `embedded attachments download in their original format, never inline (VW-06)`() {
        val file = fixtures.resolve("xrechnung/instances/standard/01.15a-INVOICE_ubl.xml").readBytes()
        val expected =
            InvoiceParser
                .parse(file)
                .invoice!!
                .supportingDocuments
                .mapNotNull { it.attachment }
        assertTrue(expected.isNotEmpty())
        val id = uploadDocument("mit-anhang.xml", file)
        val attachments =
            client
                .get("/api/v1/documents/$id/view")
                .tree
                .path("attachments")
                .toList()
        assertEquals(expected.size, attachments.size)
        attachments.zip(expected).forEach { (listed, attachment) ->
            val response = rawGet("/api/v1/documents/$id/attachments/${listed.path("index").asInt()}")
            assertEquals(200, response.statusCode())
            assertContentEquals(attachment.content, response.body(), "bytes equal the decoded Base64")
            assertTrue(
                response
                    .headers()
                    .firstValue("Content-Disposition")
                    .get()
                    .startsWith("attachment"),
            )
            assertEquals("default-src 'none'; sandbox", response.headers().firstValue("Content-Security-Policy").get())
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").get())
            attachment.mimeCode?.let {
                assertTrue(
                    response
                        .headers()
                        .firstValue("Content-Type")
                        .get()
                        .startsWith(it),
                )
            }
        }
        assertEquals(404, client.get("/api/v1/documents/$id/attachments/99").status)
    }

    @Test
    fun `status changes are recorded with user, time and note, and audited (AR-03, AR-02)`() {
        val id = uploadDocument("status.xml", Samples.xrechnung("ST-${UUID.randomUUID()}"))
        val changed =
            client.send(
                "PATCH",
                "/api/v1/documents/$id",
                mapOf(
                    "status" to "CHECKED",
                    "statusNote" to "Betrag stimmt",
                    "tags" to listOf("Material", " Material ", "Büro"),
                    "note" to "Rückfrage",
                ),
            )
        assertEquals(200, changed.status, changed.toString())
        with(changed.tree) {
            assertEquals("CHECKED", path("status").asString())
            assertEquals(listOf("Material", "Büro"), path("tags").toList().map { it.asString() })
            assertEquals("Rückfrage", path("note").asString())
            val event = path("history").single()
            assertEquals("NEW", event.path("fromStatus").asString())
            assertEquals("CHECKED", event.path("toStatus").asString())
            assertEquals("Betrag stimmt", event.path("note").asString())
            assertEquals("Test documents", event.path("userName").asString())
            assertTrue(event.path("at").asString().isNotBlank())
        }
        // Same status again: no new entry.
        client.send("PATCH", "/api/v1/documents/$id", mapOf("status" to "CHECKED"))
        assertEquals(
            1,
            client
                .get("/api/v1/documents/$id")
                .tree
                .path("history")
                .size(),
        )
        assertEquals(
            1,
            count("SELECT count(*) FROM audit_log WHERE workspace_id = ? AND action = 'document.status.changed'", user.workspaceId),
        )

        val second = uploadDocument("status-2.xml", Samples.xrechnung("ST2-${UUID.randomUUID()}"))
        val bulk =
            client.post(
                "/api/v1/documents/status",
                mapOf(
                    "documentIds" to listOf(id, second, UUID.randomUUID()),
                    "status" to "APPROVED",
                ),
            )
        assertEquals(2, bulk.tree.path("changed").asInt(), bulk.toString())
        assertEquals(
            "APPROVED",
            client
                .get("/api/v1/documents/$second")
                .tree
                .path("status")
                .asString(),
        )
        val tagged =
            client
                .get("/api/v1/documents?tag=Material")
                .tree
                .path("items")
                .toList()
                .map { it.path("id").asString() }
        assertEquals(listOf(id), tagged)
        assertTrue(
            client
                .get("/api/v1/documents?status=APPROVED")
                .tree
                .path("items")
                .any { it.path("id").asString() == second },
        )
    }

    @Test
    fun `revalidation adds a new result and keeps the earlier one (BR-07)`() {
        val id = uploadDocument("again.xml", Samples.xrechnung("RV-${UUID.randomUUID()}"))
        val queued = client.post("/api/v1/documents/$id/revalidate")
        assertEquals(202, queued.status, queued.toString())
        assertEquals("PENDING", queued.tree.path("processing").asString())
        WorkerContext.drain()
        assertEquals(
            "DONE",
            client
                .get("/api/v1/documents/$id")
                .tree
                .path("processing")
                .asString(),
        )
        assertEquals(2, count("SELECT count(*) FROM validation_result WHERE document_id = ?::uuid", id))
    }

    @Test
    fun `a search over 10,000 invoices answers in under a second (AR-04)`() {
        val searcher = client()
        val owner = accounts.enrolled("search", searcher)
        seedInvoices(owner.workspaceId, 10_000)
        searcher.get("/api/v1/documents?limit=1") // warm-up (first request of this session)

        fun timed(query: String): Pair<Response, Duration> {
            val started = System.nanoTime()
            val response = searcher.get("/api/v1/documents?$query")
            return response to Duration.ofNanos(System.nanoTime() - started)
        }
        val checks =
            mapOf(
                "q=Sonnenschein" to
                    { r: Response -> r.tree.path("items").all { it.path("sellerName").asString().contains("Sonnenschein") } },
                "q=Sonenschein" to { r: Response -> r.tree.path("items").size() > 0 }, // typo: trigram similarity
                "q=S-4711" to { r: Response -> r.tree.path("items").any { it.path("invoiceNumber").asString() == "S-4711" } },
                "q=Fassadenfarbe" to { r: Response -> r.tree.path("items").size() > 0 }, // item name, full text
                "status=PAID&from=2026-03-01&to=2026-03-31" to { r: Response ->
                    r.tree.path("items").all {
                        it.path("status").asString() == "PAID" &&
                            it.path("issueDate").asString().startsWith("2026-03")
                    }
                },
            )
        checks.forEach { (query, check) ->
            val (response, took) = timed(query)
            assertEquals(200, response.status, "$query: $response")
            assertTrue(check(response), "$query: unexpected result")
            assertTrue(took < Duration.ofSeconds(1), "$query took $took")
        }
    }

    @Test
    fun `the viewer data of an invoice with 500 lines arrives in under 2 seconds (NF-07)`() {
        val id = uploadDocument("gross.xml", invoiceWithLines(500))
        val started = System.nanoTime()
        val view = client.get("/api/v1/documents/$id/view")
        val document = client.get("/api/v1/documents/$id")
        val took = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(200, view.status)
        assertEquals(200, document.status)
        assertEquals(
            500,
            view.tree
                .path("sections")
                .single { it.path("id").asString() == "BG-25" }
                .path("sections")
                .size(),
        )
        assertTrue(took < Duration.ofSeconds(2), "took $took")
    }

    // --- helpers ------------------------------------------------------------------------

    private fun uploadDocument(
        name: String,
        bytes: ByteArray,
    ): String {
        val started = client.post("/api/v1/uploads", mapOf("fileName" to name, "size" to bytes.size, "contentType" to "application/xml"))
        assertEquals(201, started.status, started.toString())
        val put = HttpRequest.newBuilder(URI.create(started.tree.path("uploadUrl").asString()))
        started.tree
            .path("uploadHeaders")
            .properties()
            .forEach { (k, v) -> put.header(k, v.asString()) }
        assertEquals(
            200,
            http.send(put.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding()).statusCode(),
        )
        val id = started.tree.path("id").asString()
        assertEquals(202, client.post("/api/v1/uploads/$id/complete").status)
        WorkerContext.drain()
        val item =
            client
                .get("/api/v1/uploads/$id")
                .tree
                .path("items")
                .single()
        return item.path("documentId").asString()
    }

    /** Raw GET with the signed-in client's cookies, for binary downloads. */
    private fun rawGet(path: String): HttpResponse<ByteArray> {
        val cookies =
            client.cookies.cookieStore.cookies
                .joinToString("; ") { "${it.name}=${it.value}" }
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Cookie", cookies)
                .header("X-Forwarded-For", client.ip)
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun fieldsOf(section: tools.jackson.databind.JsonNode): List<Triple<String, String, String>> =
        section.path("fields").toList().map { Triple(it.path("bt").asString(), it.path("label").asString(), it.path("value").asString()) } +
            section.path("sections").toList().flatMap { fieldsOf(it) }

    /** The official sample with its two lines replaced by [n] copies of the first one. */
    private fun invoiceWithLines(n: Int): ByteArray {
        val xml = Samples.xrechnungUbl.replaceFirst("<cbc:ID>123456XX</cbc:ID>", "<cbc:ID>BIG-${UUID.randomUUID()}</cbc:ID>")
        val line = Regex("<cac:InvoiceLine>.*?</cac:InvoiceLine>", RegexOption.DOT_MATCHES_ALL)
        val first = line.find(xml)!!.value
        val start = xml.indexOf("<cac:InvoiceLine>")
        val end = xml.lastIndexOf("</cac:InvoiceLine>") + "</cac:InvoiceLine>".length
        val lines = (1..n).joinToString("\n") { i -> first.replaceFirst(Regex("<cbc:ID>[^<]*</cbc:ID>"), "<cbc:ID>$i</cbc:ID>") }
        return (xml.substring(0, start) + lines + xml.substring(end)).toByteArray()
    }

    /**
     * Writes [n] parsed invoices directly (the worker isn't the point of this test). The
     * originals are marked size_bytes = 1 like other rows without an object in storage, so
     * the archive integrity check skips them (TestDatabase.removeForgedOriginals).
     */
    private fun seedInvoices(
        workspaceId: UUID,
        n: Int,
    ) {
        val sha = "md5('$workspaceId' || i) || md5(i || '$workspaceId')"
        TestDatabase.update(
            """
            INSERT INTO stored_object (workspace_id, sha256, size_bytes, media_type, storage_key, retention_until, object_lock)
            SELECT ?, $sha, 1, 'application/xml', 'seed/' || i, '2034-12-31', 'NONE' FROM generate_series(1, $n) i
            """.trimIndent(),
            workspaceId,
        )
        TestDatabase.update(
            """
            INSERT INTO document (id, workspace_id, direction, doc_type, format, sha256, file_name, media_type, size_bytes,
                                  status, processing, received_via, created_at)
            SELECT md5('doc' || i || '$workspaceId')::uuid, ?, 'IN', 'INVOICE', 'XRECHNUNG_UBL', $sha, 'rechnung-' || i || '.xml',
                   'application/xml', 1, (ARRAY['NEW','CHECKED','APPROVED','PAID'])[1 + i % 4], 'DONE', 'UPLOAD',
                   now() - make_interval(mins => i)
              FROM generate_series(1, $n) i
            """.trimIndent(),
            workspaceId,
        )
        TestDatabase.update(
            """
            INSERT INTO parsed_invoice (document_id, workspace_id, invoice_no, issue_date, currency, seller_name, seller_tax_id,
                                        buyer_name, gross, data, search_text)
            SELECT md5('doc' || i || '$workspaceId')::uuid, ?, 'S-' || i, DATE '2026-01-01' + (i % 300),
                   'EUR', CASE WHEN i % 997 = 0 THEN 'Malerbetrieb Sonnenschein GmbH' ELSE 'Lieferant ' || (i % 500) || ' GmbH' END,
                   'DE' || lpad((i % 500)::text, 9, '0'), 'Kunde KG', (i % 1000) + 0.5, '{}',
                   'S-' || i || ' Lieferant ' || (i % 500) || CASE WHEN i % 50 = 0 THEN ' Fassadenfarbe weiß' ELSE ' Schrauben' END
              FROM generate_series(1, $n) i
            """.trimIndent(),
            workspaceId,
        )
        TestDatabase.update("ANALYZE document")
        TestDatabase.update("ANALYZE parsed_invoice")
    }

    private fun count(
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
