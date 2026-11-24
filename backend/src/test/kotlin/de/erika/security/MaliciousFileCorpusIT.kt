package de.erika.security

import com.sun.net.httpserver.HttpServer
import de.erika.support.ApiIntegrationTest
import de.erika.support.Response
import de.erika.support.Samples
import de.erika.support.TestClient
import de.erika.support.WorkerContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * NF-04 malicious-file corpus, through the whole pipeline (upload → virus scan → archive →
 * parser → KoSIT/veraPDF validation → viewer data → download). Every XML attack points at a
 * canary HTTP server: neither the parser nor the validator may ever contact it, and every
 * file must end in a defined state (rejected, or archived as unreadable/not an e-invoice)
 * without hanging the worker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaliciousFileCorpusIT : ApiIntegrationTest() {
    private lateinit var client: TestClient
    private val http = HttpClient.newHttpClient()
    private val canaryHits = CopyOnWriteArrayList<String>()
    private lateinit var canary: HttpServer
    private val canaryUrl get() = "http://127.0.0.1:${canary.address.port}"

    @BeforeAll
    fun setUp() {
        canary =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { ex ->
                    canaryHits += ex.requestURI.toString()
                    val body = "<!ENTITY leak \"LEAKED\">".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                start()
            }
        client = client()
        accounts.enrolled("malicious", client)
    }

    @AfterAll
    fun tearDown() = canary.stop(0)

    private val invoice get() = Samples.xrechnung("EVIL-${UUID.randomUUID().toString().take(8)}").decodeToString()

    /** The XRechnung sample with [doctype] inserted after the XML declaration (and [body] replacing BT-1 when given). */
    private fun withDoctype(
        doctype: String,
        number: String? = null,
    ): ByteArray {
        val xml = invoice
        val end = xml.indexOf("?>") + 2
        val patched = xml.substring(0, end) + "\n" + doctype + xml.substring(end)
        return (number?.let { patched.replaceFirst(Regex("<cbc:ID>EVIL-[^<]+</cbc:ID>"), "<cbc:ID>$it</cbc:ID>") } ?: patched).toByteArray()
    }

    private fun xmlAttacks(): List<Pair<String, ByteArray>> =
        listOf(
            "XXE: external general entity" to
                withDoctype("""<!DOCTYPE Invoice [<!ENTITY xxe SYSTEM "$canaryUrl/xxe">]>""", "&xxe;"),
            "XXE: external parameter entity" to
                withDoctype("""<!DOCTYPE Invoice [<!ENTITY % remote SYSTEM "$canaryUrl/param"> %remote;]>"""),
            "external DTD" to withDoctype("""<!DOCTYPE Invoice SYSTEM "$canaryUrl/invoice.dtd">"""),
            "XXE: local file" to withDoctype("""<!DOCTYPE Invoice [<!ENTITY f SYSTEM "file:///etc/passwd">]>""", "&f;"),
            "billion laughs" to
                withDoctype(
                    "<!DOCTYPE Invoice [<!ENTITY a \"aaaaaaaaaa\">" +
                        (1..9).joinToString("") { "<!ENTITY a$it \"${"&a${if (it == 1) "" else it - 1};".repeat(10)}\">" } +
                        "]>",
                    "&a9;",
                ),
            "XInclude" to
                invoice
                    .replaceFirst(
                        "<cbc:Note>",
                        "<xi:include xmlns:xi=\"http://www.w3.org/2001/XInclude\" href=\"$canaryUrl/xinclude\" parse=\"text\"/><cbc:Note>",
                    ).ifEmpty { invoice }
                    .toByteArray(),
            "remote schema location" to
                invoice
                    .replaceFirst(
                        "xmlns:cbc=",
                        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"urn:x $canaryUrl/schema.xsd\" xmlns:cbc=",
                    ).toByteArray(),
        )

    @TestFactory
    fun `XML attacks never reach out and end in a defined state`(): List<DynamicTest> =
        xmlAttacks().map { (name, bytes) ->
            DynamicTest.dynamicTest(name) {
                val before = canaryHits.size
                val item = upload("angriff.xml", bytes, "application/xml").tree.path("items").single()
                assertEquals(before, canaryHits.size, "$name: contacted $canaryHits")
                if (item.path("outcome").asString() == "IMPORTED") {
                    val doc = client.get("/api/v1/documents/${item.path("documentId").asString()}").tree
                    assertNotEquals("PENDING", doc.path("processing").asString(), "$name: still pending")
                    assertNotEquals("VALID", doc.path("validation").path("level").asString(), "$name: must not pass as valid")
                    assertTrue(!doc.toString().contains("LEAKED") && !doc.toString().contains("root:"), "$name: leaked content")
                }
            }
        }

    @TestFactory
    fun `other malicious files are refused or stored inertly`(): List<DynamicTest> =
        listOf(
            DynamicTest.dynamicTest("HTML with script named .pdf is refused") {
                val item =
                    upload("rechnung.pdf", "<html><script>alert(1)</script></html>".toByteArray(), "application/pdf")
                        .tree
                        .path("items")
                        .single()
                assertEquals("REJECTED", item.path("outcome").asString(), item.toString())
            },
            DynamicTest.dynamicTest("SVG with script is refused") {
                val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>"""
                assertEquals(
                    "REJECTED",
                    upload("bild.svg", svg.toByteArray(), "image/svg+xml")
                        .tree
                        .path("items")
                        .single()
                        .path("outcome")
                        .asString(),
                )
            },
            DynamicTest.dynamicTest("EICAR inside a ZIP is refused") {
                val items = upload("paket.zip", Samples.zip("rechnung.xml" to Samples.eicar()), "application/zip").tree
                val outcomes = items.path("items").values().map { it.path("outcome").asString() + ":" + it.path("reason").asString() }
                assertTrue(outcomes.isNotEmpty() && outcomes.all { it.startsWith("REJECTED") }, "$items")
            },
            DynamicTest.dynamicTest("ZIP three levels deep is refused") {
                val inner = Samples.zip("tief.xml" to invoice.toByteArray())
                val nested = Samples.zip("b.zip" to Samples.zip("c.zip" to inner))
                val result = upload("a.zip", nested, "application/zip").tree
                assertTrue(result.path("items").values().none { it.path("outcome").asString() == "IMPORTED" }, "$result")
            },
            DynamicTest.dynamicTest("PDF with JavaScript is archived as a plain PDF and only ever downloaded, never rendered") {
                val pdf =
                    PDDocument().use { doc ->
                        doc.addPage(PDPage())
                        doc.documentCatalog.openAction = PDActionJavaScript("app.alert('pwned')")
                        doc.documentInformation.title = "js ${UUID.randomUUID()}"
                        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
                    }
                val item = upload("rechnung.pdf", pdf, "application/pdf").tree.path("items").single()
                assertEquals("IMPORTED", item.path("outcome").asString())
                val id = item.path("documentId").asString()
                assertEquals(
                    "PDF_ONLY",
                    client
                        .get("/api/v1/documents/$id")
                        .tree
                        .path("format")
                        .asString(),
                )
                val download = rawGet("/api/v1/documents/$id/original")
                assertTrue(
                    download
                        .headers()
                        .firstValue("Content-Disposition")
                        .orElse("")
                        .startsWith("attachment"),
                )
                assertEquals("nosniff", download.headers().firstValue("X-Content-Type-Options").orElse(""))
                assertTrue(
                    download
                        .headers()
                        .firstValue("Content-Security-Policy")
                        .orElse("")
                        .contains("sandbox"),
                )
            },
        )

    private fun upload(
        name: String,
        bytes: ByteArray,
        contentType: String,
    ): Response {
        val started = client.post("/api/v1/uploads", mapOf("fileName" to name, "size" to bytes.size, "contentType" to contentType))
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
        return client.get("/api/v1/uploads/$id")
    }

    private fun rawGet(path: String): HttpResponse<ByteArray> {
        val cookies =
            client.cookies.cookieStore.cookies
                .joinToString("; ") { "${it.name}=${it.value}" }
        return http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Cookie", cookies)
                .header("X-Forwarded-For", client.ip)
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
    }
}
