package de.erika.export

import de.erika.support.ApiIntegrationTest
import de.erika.support.CreatorSupport
import de.erika.support.Response
import de.erika.support.Samples
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 9 exports through the API and the worker: the period export with originals, index and
 * DATEV Buchungsstapel (EX-01), the full account export checked against the archive (EX-04),
 * download only after sign-in and only until expiry, tax advisors exporting and commenting.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportIT : ApiIntegrationTest() {
    private lateinit var owner: TestClient
    private lateinit var user: TestUser
    private lateinit var advisor: TestClient
    private lateinit var c: CreatorSupport
    private val http: HttpClient = HttpClient.newHttpClient()
    private val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
    private val key get() = "Idempotency-Key" to UUID.randomUUID().toString()
    private lateinit var incomingNumber: String
    private lateinit var issuedNumber: String

    @BeforeAll
    fun data() {
        owner = client()
        user = accounts.enrolled("exporting", owner, workspaceName = "Export Malerei")
        advisor = accounts.invited(owner, "ADVISOR").second
        c = CreatorSupport(owner, port)
        c.completeSeller()
        // Issued: 8 h × 55.00 at 19 % and 2 × 24.99 at 7 %, then cancelled (381).
        val issued = c.createAndIssue()
        issuedNumber =
            issued.tree
                .path("issued")
                .path("number")
                .asString()
        val cancellation =
            owner
                .post(
                    "/api/v1/invoices/${issued.tree.path("id").asString()}/credit-note",
                    mapOf("kind" to "CANCELLATION"),
                    key,
                ).tree
                .path("id")
                .asString()
        c.issue(cancellation)
        // Received: the official XRechnung sample (7 %), dated today, and a plain PDF.
        incomingNumber = "EXP-${UUID.randomUUID().toString().take(6)}"
        upload(
            "eingang.xml",
            Samples
                .xrechnung(incomingNumber)
                .decodeToString()
                .replace("2016-04-04", today.toString())
                .toByteArray(),
            "application/xml",
        )
        upload("scan.pdf", Samples.plainPdf(), "application/pdf")
        val settings = owner.get("/api/v1/datev-settings")
        val saved =
            owner.put(
                "/api/v1/datev-settings",
                mapOf(
                    "consultantNumber" to 1234567,
                    "clientNumber" to 10001,
                    "chart" to "SKR03",
                    "fiscalYearStartMonth" to 1,
                    "accountLength" to 4,
                ),
                "If-Match" to settings.header("ETag")!!,
            )
        assertEquals(200, saved.status, saved.toString())
        assertTrue(saved.tree.path("complete").asBoolean())
    }

    @Test
    fun `a period export contains the originals, an index and a DATEV Buchungsstapel (EX-01)`() {
        val export =
            runExport(
                owner,
                mapOf(
                    "kind" to "PERIOD",
                    "from" to maxOf(today.minusDays(30), today.withDayOfYear(1)).toString(),
                    "to" to today.toString(),
                    "scope" to "ALL",
                ),
            )
        assertEquals(4, export.tree.path("documentCount").asInt())
        val zip = download(owner, export)
        assertEquals(export.tree.path("sha256").asString(), sha256(zip), "the ZIP is what the export recorded")
        val entries = unzip(zip)

        val index =
            entries
                .getValue("index.csv")
                .decodeToString()
                .removePrefix("")
                .trim()
                .lines()
        assertEquals(5, index.size, index.joinToString("\n"))
        val rows = index.drop(1).map { line -> line.split("\";\"").map { it.trim('"') } }
        rows.forEach { row ->
            val file = assertNotNull(entries[row[0]], "file ${row[0]} in the ZIP")
            assertEquals(row[1], sha256(file), "index SHA-256 of ${row[0]}")
        }
        assertEquals(2, rows.count { it[2] == "Eingang" })
        assertTrue(rows.any { it[4] == incomingNumber && it[0].startsWith("Belege/Eingang/") })
        assertTrue(rows.any { it[4] == issuedNumber && it[0].startsWith("Belege/Ausgang/") })

        val datevName = entries.keys.single { it.startsWith("DATEV/EXTF_Buchungsstapel_") }
        val datev = String(entries.getValue(datevName), DatevCsv.CHARSET).split("\r\n").filter { it.isNotEmpty() }
        val header = datev[0].split(";")
        assertEquals(listOf("\"EXTF\"", "700", "21", "\"Buchungsstapel\"", "13"), header.take(5))
        assertEquals(31, header.size)
        assertEquals("1234567", header[10])
        assertEquals("10001", header[11])
        assertEquals("${today.year}0101", header[12])
        assertEquals("\"03\"", header[26])
        assertEquals(125, datev[1].split(";").size)
        val bookings = datev.drop(2).map { it.split(";") }
        bookings.forEach { assertEquals(125, it.size) }

        fun line(
            number: String,
            amount: String,
        ) = bookings.single { it[10] == "\"$number\"" && it[0] == amount }
        // Issued invoice: debtor debited, revenue 8200, output VAT 19 % (3) and 7 % (2).
        line(issuedNumber, "523,60").let { assertEquals(listOf("\"S\"", "10000", "8200", "\"3\""), listOf(it[1], it[6], it[7], it[8])) }
        line(issuedNumber, "53,48").let { assertEquals("\"2\"", it[8]) }
        // The cancellation (381) turns both lines round.
        val cancellationNumber = rows.first { it[2] == "Ausgang" && it[4] != issuedNumber }[4]
        assertEquals("\"H\"", line(cancellationNumber, "523,60")[1])
        // Received invoice: creditor credited, expense 4900, input VAT 7 % (8).
        val incoming = bookings.single { it[10] == "\"$incomingNumber\"" }
        assertEquals(listOf("\"H\"", "70000", "4900", "\"8\""), listOf(incoming[1], incoming[6], incoming[7], incoming[8]))
        assertEquals(
            today.format(
                java.time.format.DateTimeFormatter
                    .ofPattern("ddMM"),
            ),
            incoming[9],
        )
        assertEquals(5, bookings.size, "the plain PDF has no amounts, so no booking")
        assertTrue(entries.containsKey("LIESMICH.txt"))
    }

    @Test
    fun `the full account export holds every archived original and all data, checked against the archive (EX-04)`() {
        val forbidden = advisor.post("/api/v1/exports", mapOf("kind" to "FULL"), key)
        assertEquals(403, forbidden.status, "owners only")
        assertEquals(201, advisor.post("/api/v1/documents/${anyDocument()}/comments", mapOf("body" to "Bitte prüfen"), key).status)

        val export = runExport(owner, mapOf("kind" to "FULL"))
        val entries = unzip(download(owner, export))
        val archived = archivedHashes()
        val files = entries.keys.filter { it.startsWith("archiv/") }
        assertEquals(archived.size, files.size, "count against the archive")
        assertEquals(archived.size, export.tree.path("documentCount").asInt())
        files.forEach { name -> assertEquals(name.removePrefix("archiv/").substringBefore('.'), sha256(entries.getValue(name)), name) }
        assertEquals(archived, files.map { it.removePrefix("archiv/").substringBefore('.') }.toSet(), "hashes against the archive")

        val manifest =
            entries
                .getValue("MANIFEST.sha256")
                .decodeToString()
                .trim()
                .lines()
        assertEquals(entries.size - 1, manifest.size)
        manifest.forEach { line ->
            val (sha, name) = line.split("  ", limit = 2)
            assertEquals(sha, sha256(entries.getValue(name)), name)
        }
        listOf("arbeitsbereich.json", "dokumente.json", "rechnungserstellung.json", "protokoll.json").forEach {
            assertTrue(entries.containsKey("daten/$it"), it)
        }
        val documents = json.readTree(entries.getValue("daten/dokumente.json"))
        assertEquals(4, documents.size())
        assertTrue(documents.values().any { d -> d.path("comments").values().any { it.path("body").asString() == "Bitte prüfen" } })
        val audit = json.readTree(entries.getValue("daten/protokoll.json")).values().toList()
        assertEquals((1..audit.size).map { it.toLong() }, audit.map { it.path("seq").asLong() }, "complete chain")
        val members = json.readTree(entries.getValue("daten/arbeitsbereich.json")).path("members")
        assertEquals(2, members.size())
        assertTrue(entries.getValue("daten/arbeitsbereich.json").decodeToString().let { "password" !in it && "totp_secret" !in it })
    }

    @Test
    fun `tax advisors export and download, only signed in, and only until the export expires`() {
        val export =
            runExport(
                advisor,
                mapOf(
                    "kind" to "PERIOD",
                    "from" to today.minusDays(7).toString(),
                    "to" to today.toString(),
                    "scope" to "IN",
                ),
            )
        assertEquals(2, export.tree.path("documentCount").asInt())
        val id = export.tree.path("id").asString()
        assertEquals(401, client().get("/api/v1/exports/$id/download").status, "no session, no download")
        assertTrue(download(advisor, export).isNotEmpty())

        TestDatabase.update("UPDATE data_export SET expires_at = now() - interval '1 minute' WHERE id = ?", UUID.fromString(id))
        val expired = advisor.get("/api/v1/exports/$id/download")
        assertEquals(410, expired.status)
        assertEquals("EXPORT_EXPIRED", expired.code)
        assertTrue(WorkerContext.bean(ExportCleanup::class.java).run() >= 1)
        assertEquals(
            "EXPIRED",
            owner
                .get("/api/v1/exports/$id")
                .tree
                .path("state")
                .asString(),
        )
        val key = "exports/${user.workspaceId}/$id.zip"
        val storage = WorkerContext.bean(de.erika.platform.storage.ObjectStorage::class.java)
        assertEquals(null, storage.get("erika-exports", key), "the ZIP is deleted")
    }

    @Test
    fun `period exports are validated`() {
        val reversed =
            owner.post(
                "/api/v1/exports",
                mapOf(
                    "kind" to "PERIOD",
                    "from" to today.toString(),
                    "to" to today.minusDays(1).toString(),
                ),
                key,
            )
        assertEquals(400, reversed.status)
        assertEquals("EXPORT_PERIOD_INVALID", reversed.code)
        assertEquals("EXPORT_PERIOD_INVALID", owner.post("/api/v1/exports", mapOf("kind" to "PERIOD"), key).code)
    }

    // --- helpers ------------------------------------------------------------------------

    private fun runExport(
        client: TestClient,
        body: Map<String, Any?>,
    ): Response {
        val created = client.post("/api/v1/exports", body, key)
        assertEquals(202, created.status, created.toString())
        assertEquals("QUEUED", created.tree.path("state").asString())
        WorkerContext.drain()
        val export = client.get("/api/v1/exports/${created.tree.path("id").asString()}")
        assertEquals("READY", export.tree.path("state").asString(), export.toString())
        return export
    }

    private fun download(
        client: TestClient,
        export: Response,
    ): ByteArray {
        val redirect = client.get("/api/v1/exports/${export.tree.path("id").asString()}/download")
        assertEquals(303, redirect.status, redirect.toString())
        val location = assertNotNull(redirect.header("Location"))
        val response = http.send(HttpRequest.newBuilder(URI.create(location)).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, response.statusCode())
        assertTrue(
            response
                .headers()
                .firstValue("Content-Disposition")
                .orElse("")
                .startsWith("attachment"),
        )
        return response.body()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.associate { it.name to zip.readAllBytes() }
        }

    private fun upload(
        name: String,
        bytes: ByteArray,
        type: String,
    ) {
        val started = owner.post("/api/v1/uploads", mapOf("fileName" to name, "size" to bytes.size, "contentType" to type))
        assertEquals(201, started.status, started.toString())
        val put = HttpRequest.newBuilder(URI.create(started.tree.path("uploadUrl").asString()))
        started.tree
            .path("uploadHeaders")
            .properties()
            .forEach { (k, v) -> put.header(k, v.asString()) }
        http.send(put.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding())
        assertEquals(202, owner.post("/api/v1/uploads/${started.tree.path("id").asString()}/complete").status)
        WorkerContext.drain()
    }

    private fun anyDocument(): String =
        owner
            .get("/api/v1/documents")
            .tree
            .path("items")
            .first()
            .path("id")
            .asString()

    private fun archivedHashes(): Set<String> =
        TestDatabase.asOwner {
            it.prepareStatement("SELECT sha256 FROM stored_object WHERE workspace_id = ?").run {
                setObject(1, user.workspaceId)
                executeQuery().run { generateSequence { if (next()) getString(1) else null }.toSet() }
            }
        }

    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
