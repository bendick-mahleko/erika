package de.erika.creator

import de.erika.invoicecore.InvoiceParser
import de.erika.platform.jobs.JobQueue
import de.erika.support.ApiIntegrationTest
import de.erika.support.CreatorSupport
import de.erika.support.Mails
import de.erika.support.MultipartFile
import de.erika.support.TestClient
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Release 1.1 for the invoice creator: logo and accent colour (CR-08), recurring invoices (CR-09). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Release11CreatorIT : ApiIntegrationTest() {
    private lateinit var client: TestClient
    private lateinit var user: TestUser
    private lateinit var c: CreatorSupport

    @Autowired lateinit var jobs: JobQueue

    @Autowired lateinit var tx: TransactionTemplate

    @BeforeAll
    fun account() {
        client = client()
        user = accounts.enrolled("release-11", client)
        c = CreatorSupport(client, port)
        c.completeSeller()
    }

    @Test
    fun `the logo and accent colour appear on the ZUGFeRD PDF, the invoice data stays the same (CR-08)`() {
        val png = MultipartFile.PNG_1X1
        assertEquals(204, client.put("/api/v1/invoice-settings/logo", MultipartFile.png()).status)
        assertEquals(
            true,
            client
                .get("/api/v1/invoice-settings")
                .tree
                .path("hasLogo")
                .asBoolean(),
        )
        val logo = c.rawGet("/api/v1/invoice-settings/logo")
        assertEquals(200, logo.statusCode())
        assertEquals("image/png", logo.headers().firstValue("Content-Type").get())
        assertContentEquals(png, logo.body())

        val notAnImage =
            client.put(
                "/api/v1/invoice-settings/logo",
                MultipartFile("file", "logo.png", "image/png", "kein Bild".toByteArray()),
            )
        assertEquals(415, notAnImage.status, notAnImage.toString())
        assertEquals("LOGO_TYPE_UNSUPPORTED", notAnImage.code)
        assertEquals(400, updateSettings(mapOf("accentColor" to "blau")).status)
        val coloured = updateSettings(mapOf("accentColor" to "#1a5fb4"))
        assertEquals(200, coloured.status, coloured.toString())
        assertEquals("#1A5FB4", coloured.tree.path("accentColor").asString())

        val issued = c.createAndIssue(c.content(format = "ZUGFERD_EN16931"))
        assertEquals("ISSUED", issued.tree.path("state").asString(), issued.toString())
        val documentId =
            issued.tree
                .path("issued")
                .path("documentId")
                .asString()
        val pdf = c.rawGet("/api/v1/documents/$documentId/original").body()
        val images =
            Loader.loadPDF(pdf).use { doc ->
                val resources = doc.getPage(0).resources
                resources.xObjectNames.count { resources.getXObject(it) is PDImageXObject }
            }
        assertEquals(1, images, "the logo is drawn on page 1")
        assertNotNull(InvoiceParser.parse(pdf).invoice, "the embedded XML is still read")
        val doc = client.get("/api/v1/documents/$documentId").tree
        assertTrue(doc.path("validation").path("level").asString() in setOf("VALID", "WARNING"), doc.toString())

        assertEquals(204, client.send("DELETE", "/api/v1/invoice-settings/logo", null).status)
        assertEquals(
            false,
            client
                .get("/api/v1/invoice-settings")
                .tree
                .path("hasLogo")
                .asBoolean(),
        )
        assertEquals(404, client.send("DELETE", "/api/v1/invoice-settings/logo", null).status)
        updateSettings(mapOf("accentColor" to null))
    }

    @Test
    fun `a recurring invoice creates a draft for each period, catches up missed runs and can be paused (CR-09)`() {
        val today = LocalDate.now(Creator.BERLIN)
        val source =
            c
                .createDraft(c.content())
                .tree
                .path("id")
                .asString()
        val created =
            client.post(
                "/api/v1/recurring-invoices",
                mapOf("sourceInvoiceId" to source, "name" to "Wartung", "interval" to "MONTHLY", "nextRun" to today.toString()),
                c.key(),
            )
        assertEquals(201, created.status, created.toString())
        val id = created.tree.path("id").asString()
        assertEquals("Kunde AG", created.tree.path("buyerName").asString())

        run(today)
        val first = client.get("/api/v1/recurring-invoices/$id").tree
        assertEquals(today.plusMonths(1).toString(), first.path("nextRun").asString())
        assertEquals(today.toString(), first.path("lastRun").asString())
        val draft = client.get("/api/v1/invoices/${first.path("lastDraftId").asString()}").tree
        assertEquals("DRAFT", draft.path("state").asString())
        assertEquals(today.toString(), draft.path("content").path("periodStart").asString())
        assertEquals(today.plusMonths(1).minusDays(1).toString(), draft.path("content").path("periodEnd").asString())
        assertTrue(draft.path("content").path("issueDate").isMissingNode || draft.path("content").path("issueDate").isNull)
        assertEquals(2, draft.path("content").path("lines").size())
        val mails = Mails.queuedTo(jdbc, user.email).filter { it["subject"] == "Neuer Rechnungsentwurf aus „Wartung“" }
        assertEquals(1, mails.size)

        // The same day again creates nothing.
        val drafts = draftCount()
        run(today)
        assertEquals(drafts, draftCount())

        // Two missed months are caught up: three drafts.
        val etag = client.get("/api/v1/recurring-invoices/$id").header("ETag")!!
        val back =
            client.put(
                "/api/v1/recurring-invoices/$id",
                mapOf("name" to "Wartung", "interval" to "MONTHLY", "nextRun" to today.minusMonths(2).toString(), "active" to true),
                "If-Match" to etag,
            )
        assertEquals(200, back.status, back.toString())
        run(today)
        assertEquals(drafts + 3, draftCount())
        assertEquals(
            today.plusMonths(1).toString(),
            client
                .get("/api/v1/recurring-invoices/$id")
                .tree
                .path("nextRun")
                .asString(),
        )

        // Paused: nothing is created; a stale version is refused.
        val stale =
            client.put(
                "/api/v1/recurring-invoices/$id",
                mapOf(
                    "name" to "x",
                    "interval" to "MONTHLY",
                    "nextRun" to today.toString(),
                    "active" to false,
                ),
                "If-Match" to etag,
            )
        assertEquals(412, stale.status, stale.toString())
        val paused =
            client.put(
                "/api/v1/recurring-invoices/$id",
                mapOf("name" to "Wartung", "interval" to "QUARTERLY", "nextRun" to today.toString(), "active" to false),
                "If-Match" to client.get("/api/v1/recurring-invoices/$id").header("ETag")!!,
            )
        assertEquals(false, paused.tree.path("active").asBoolean())
        run(today.plusMonths(6))
        assertEquals(drafts + 3, draftCount())

        assertEquals(204, client.send("DELETE", "/api/v1/recurring-invoices/$id", null).status)
        assertEquals(404, client.get("/api/v1/recurring-invoices/$id").status)
    }

    private fun run(day: LocalDate) {
        tx.execute { jobs.enqueue(RecurringInvoicesJobHandler.TYPE, RecurringInvoicesJobHandler.Payload(day), maxAttempts = 1) }
        WorkerContext.drain()
    }

    private fun draftCount() =
        client
            .get("/api/v1/invoices?state=DRAFT&limit=100")
            .tree
            .path("items")
            .size()

    private fun updateSettings(changes: Map<String, Any?>): de.erika.support.Response {
        val current = client.get("/api/v1/invoice-settings")
        val t = current.tree
        val body =
            mapOf(
                "contactName" to t.path("contactName").asString(),
                "contactPhone" to t.path("contactPhone").asString(),
                "contactEmail" to t.path("contactEmail").asString(),
                "iban" to t.path("iban").asString(),
                "bic" to t.path("bic").asString(),
                "kleinunternehmer" to t.path("kleinunternehmer").asBoolean(),
                "numberPattern" to t.path("numberPattern").asString(),
                "creditNotePattern" to t.path("creditNotePattern").asString(),
                "defaultFormat" to t.path("defaultFormat").asString(),
                "paymentTermsDays" to t.path("paymentTermsDays").asInt(),
            ) + changes
        return client.put("/api/v1/invoice-settings", body, "If-Match" to current.header("ETag")!!)
    }
}
