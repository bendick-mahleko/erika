package de.erika.reports

import de.erika.invoicecore.InvoiceParser
import de.erika.support.CreatorSupport
import de.erika.support.TestDatabase
import de.erika.support.WorkerContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MARA Stage 8: invoicing from signed reports through ERIKA's invoice creator (M-IV-01 to
 * M-IV-07, M-BR-04, M-BR-05, M-BR-07). The reports are recorded and signed by a test phone as in
 * the app; the invoice is issued by `InvoiceIssuer` in the worker, validated and archived.
 */
class ReportInvoicingIT : SignedReportTest() {
    private val bom = 0xFEFF.toChar().toString()

    private fun signed(
        prefix: String,
        customerType: String = "BUSINESS",
    ): Pair<Firm, String> {
        val f = firm(prefix, customerType)
        CreatorSupport(f.owner, port).completeSeller()
        record(f)
        val report = f.report()
        assertEquals("APPLIED", sign(f, report).path("status").asString())
        WorkerContext.drain()
        return f to report
    }

    private fun settings(
        f: Firm,
        body: Map<String, Any?>,
    ) {
        val r = f.owner.put("/api/v1/report-invoicing/settings", body)
        assertEquals(200, r.status, r.toString())
    }

    private fun preview(
        f: Firm,
        vararg reports: String,
    ): JsonNode {
        val r = f.owner.post("/api/v1/report-invoicing/preview", mapOf("reportIds" to reports.toList()))
        assertEquals(200, r.status, r.toString())
        return r.tree
    }

    /** The preview's lines with a price for every line, as the office sends them. */
    private fun linesOf(preview: JsonNode): List<Map<String, Any?>> =
        preview.path("lines").values().map { l ->
            mapOf(
                "name" to l.path("name").asString(),
                "description" to l.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asString(),
                "quantity" to l.path("quantity").asString(),
                "unitCode" to l.path("unitCode").asString(),
                "netPrice" to if (l.path("priceMissing").asBoolean()) "0.80" else l.path("netPrice").asString(),
                "vatRate" to l.path("vatRate").asString(),
                "laborCost" to l.path("laborCost").asBoolean(),
            )
        }

    private fun create(
        f: Firm,
        reports: List<String>,
        lines: List<Map<String, Any?>>,
        propertyRelated: Boolean = false,
    ) = f.owner.post(
        "/api/v1/report-invoicing/invoices",
        mapOf("reportIds" to reports, "lines" to lines, "propertyRelated" to propertyRelated),
        key(),
    )

    private fun issued(
        f: Firm,
        invoiceId: String,
    ): JsonNode {
        WorkerContext.drain()
        val invoice = f.owner.get("/api/v1/invoices/$invoiceId").tree
        assertEquals("ISSUED", invoice.path("state").asString(), invoice.toString())
        return invoice
    }

    private fun original(
        f: Firm,
        invoice: JsonNode,
    ): ByteArray {
        val documentId = invoice.path("issued").path("documentId").asString()
        return CreatorSupport(f.owner, port).rawGet("/api/v1/documents/$documentId/original").body()
    }

    /** With `ERIKA_EXAMPLES_DIR` set, keeps the invoice as an example for the tax advisor (docs/mara/examples). */
    private fun example(
        name: String,
        bytes: ByteArray,
    ) {
        System.getenv("ERIKA_EXAMPLES_DIR")?.let {
            java.io
                .File(it)
                .apply { mkdirs() }
                .resolve(name)
                .writeBytes(bytes)
        }
    }

    @Test
    fun `signed report to issued e-invoice with the report PDF embedded, then the work order is INVOICED`() {
        val (f, report) = signed("rinv")
        settings(
            f,
            mapOf("billingUnitMinutes" to 15, "defaultHourlyRate" to "58.00", "travelMode" to "FLAT", "travelFlatAmount" to "35.00"),
        )
        assertEquals(
            "58",
            f.owner
                .get("/api/v1/report-invoicing/settings")
                .tree
                .path("defaultHourlyRate")
                .asString(),
        )

        val billable = f.owner.get("/api/v1/report-invoicing/billable").tree
        assertEquals(listOf(report), billable.path("items").values().map { it.path("reportId").asString() })
        assertEquals(
            "Familie Sommer",
            billable
                .path("items")
                .first()
                .path("customerName")
                .asString(),
        )

        // 85 minutes of work, billed by the quarter hour: 1.5 h × 58.00; one flat travel amount; the free-text material has no price.
        val p = preview(f, report)
        val lines = p.path("lines").values().toList()
        assertEquals(listOf("WORK", "TRAVEL", "MATERIAL"), lines.map { it.path("kind").asString() })
        assertEquals("1.5", lines[0].path("quantity").asString())
        assertEquals("87.00", lines[0].path("net").asString())
        assertTrue(lines[0].path("laborCost").asBoolean())
        assertEquals("35.00", lines[1].path("net").asString())
        assertEquals("LS", lines[1].path("unitCode").asString())
        assertTrue(lines[2].path("priceMissing").asBoolean())
        assertEquals("122.00", p.path("totalNet").asString())
        assertEquals(false, p.path("privateCustomer").asBoolean())

        val created = create(f, listOf(report), linesOf(p))
        assertEquals(201, created.status, created.toString())
        val invoiceId = created.tree.path("invoiceId").asString()
        val invoice = issued(f, invoiceId)

        val file = original(f, invoice)
        example("Rechnung-Geschaeftskunde-ZUGFeRD.pdf", file)
        val parsed = assertNotNull(InvoiceParser.parse(file).invoice)
        val code =
            f.device
                .record(
                    "report_signature",
                    DerivedIds.of(UUID.fromString(report), "signature").toString(),
                )!!
                .data["verificationCode"]
        val attachment = parsed.supportingDocuments.single()
        assertEquals(code, attachment.reference)
        assertEquals("Arbeitsbericht-$code.pdf", attachment.attachment?.filename)
        assertEquals("application/pdf", attachment.attachment?.mimeCode)
        assertTrue(parsed.notes.any { it.text.contains(code as String) }, "the note names the report")
        val documentId = invoice.path("issued").path("documentId").asString()
        val doc = f.owner.get("/api/v1/documents/$documentId").tree
        assertTrue(doc.path("validation").path("level").asString() in setOf("VALID", "WARNING"), doc.toString())

        assertEquals(
            "INVOICED",
            f.owner
                .get("/api/v1/work-orders/${f.order}")
                .tree
                .path("status")
                .asString(),
        )
        assertTrue(
            f.owner
                .get("/api/v1/report-invoicing/billable")
                .tree
                .path("items")
                .isEmpty,
        )
        val summary = f.owner.get("/api/v1/work-orders/${f.order}/report").tree
        assertEquals(
            invoiceId,
            summary
                .path("report")
                .path("invoice")
                .path("invoiceId")
                .asString(),
        )
        assertEquals(
            invoice.path("issued").path("number").asString(),
            summary
                .path("report")
                .path("invoice")
                .path("number")
                .asString(),
        )

        val again = create(f, listOf(report), linesOf(p))
        assertEquals(409, again.status, again.toString())
        assertEquals("ALREADY_INVOICED", again.code)
    }

    @Test
    fun `a private customer's invoice shows the 35a labour share and the retention note`() {
        val (f, report) = signed("rpriv", customerType = "PRIVATE")
        settings(f, mapOf("billingUnitMinutes" to 30, "defaultHourlyRate" to "60.00", "travelMode" to "TIME"))
        // The customer's own setting wins: no travel billed.
        val own = f.owner.put("/api/v1/report-invoicing/customers/${customerOf(f)}", mapOf("travelMode" to "NONE"))
        assertEquals(200, own.status, own.toString())
        val p = preview(f, report)
        assertTrue(p.path("privateCustomer").asBoolean())
        assertEquals(listOf("WORK", "MATERIAL"), p.path("lines").values().map { it.path("kind").asString() })
        // 85 minutes in half hours: 1.5 h × 60.00
        assertEquals(
            "90.00",
            p
                .path("lines")
                .first()
                .path("net")
                .asString(),
        )

        val created = create(f, listOf(report), linesOf(p), propertyRelated = true)
        assertEquals(201, created.status, created.toString())
        val invoice = issued(f, created.tree.path("invoiceId").asString())
        assertEquals("ZUGFERD_EN16931", invoice.path("content").path("format").asString(), invoice.toString())
        val file = original(f, invoice)
        example("Rechnung-Privatkunde-35a-Grundstueck-ZUGFeRD.pdf", file)
        val notes = assertNotNull(InvoiceParser.parse(file).invoice).notes.map { it.text }
        // Labour 90.00 net + 17.10 VAT.
        assertTrue(notes.any { it.contains("§ 35a EStG") && it.contains("107,10") }, notes.toString())
        assertTrue(notes.any { it.contains("§ 14b Abs. 1 Satz 5 UStG") }, notes.toString())
    }

    @Test
    fun `without work on a property the private invoice has the 35a share but no retention note`() {
        val (f, report) = signed("rpriv2", customerType = "PRIVATE")
        settings(
            f,
            mapOf("billingUnitMinutes" to 15, "defaultHourlyRate" to "58.00", "travelMode" to "FLAT", "travelFlatAmount" to "35.00"),
        )
        val created = create(f, listOf(report), linesOf(preview(f, report)), propertyRelated = false)
        assertEquals(201, created.status, created.toString())
        val file = original(f, issued(f, created.tree.path("invoiceId").asString()))
        example("Rechnung-Privatkunde-35a-ZUGFeRD.pdf", file)
        val notes = assertNotNull(InvoiceParser.parse(file).invoice).notes.map { it.text }
        // Labour and travel: 87.00 + 35.00 net, plus 19 % VAT = 145.18.
        assertTrue(notes.any { it.contains("§ 35a EStG") && it.contains("145,18") }, notes.toString())
        assertTrue(notes.none { it.contains("§ 14b") }, notes.toString())
    }

    @Test
    fun `overdue invoices get at most three reminders by email`() {
        val (f, report) = signed("rdue")
        settings(f, mapOf("billingUnitMinutes" to 15, "defaultHourlyRate" to "50.00", "travelMode" to "NONE"))
        val created = create(f, listOf(report), linesOf(preview(f, report)))
        assertEquals(201, created.status, created.toString())
        val invoiceId = created.tree.path("invoiceId").asString()
        issued(f, invoiceId)
        val notYet = f.owner.post("/api/v1/invoices/$invoiceId/reminders", mapOf<String, Any?>(), key())
        assertEquals(409, notYet.status, notYet.toString())
        assertEquals("INVOICE_NOT_OVERDUE", notYet.code)

        TestDatabase.update("UPDATE invoice SET due_date = current_date - 10 WHERE id = ?", UUID.fromString(invoiceId))
        val overdue =
            f.owner.get("/api/v1/invoices/overdue").tree.path("items").values().single {
                it.path("invoiceId").asString() ==
                    invoiceId
            }
        assertEquals(10, overdue.path("daysOverdue").asInt())
        assertTrue(overdue.path("reminders").isEmpty)

        for (level in 1..3) {
            val sent = f.owner.post("/api/v1/invoices/$invoiceId/reminders", mapOf("to" to "buchhaltung-rdue@example.test"), key())
            assertEquals(201, sent.status, sent.toString())
            assertEquals(level, sent.tree.path("level").asInt())
        }
        val exhausted = f.owner.post("/api/v1/invoices/$invoiceId/reminders", mapOf<String, Any?>(), key())
        assertEquals(409, exhausted.status)
        assertEquals("REMINDERS_EXHAUSTED", exhausted.code)
        val after =
            f.owner.get("/api/v1/invoices/overdue").tree.path("items").values().single {
                it.path("invoiceId").asString() ==
                    invoiceId
            }
        assertEquals(listOf(1, 2, 3), after.path("reminders").values().map { it.path("level").asInt() })
        assertEquals(
            3,
            TestDatabase.count(
                "SELECT count(*) FROM invoice_delivery WHERE invoice_id = ? AND recipient = 'buchhaltung-rdue@example.test'",
                UUID.fromString(invoiceId),
            ),
        )
        WorkerContext.drain()
    }

    @Test
    fun `raw times as CSV for payroll, owner only`() {
        val (f, _) = signed("rcsv")
        val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
        val csv = CreatorSupport(f.owner, port).rawGet("/api/v1/reports/time-entries.csv?from=${today.minusDays(1)}&to=$today")
        assertEquals(200, csv.statusCode())
        assertTrue(
            csv
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .startsWith("text/csv"),
        )
        val text = String(csv.body(), Charsets.UTF_8).removePrefix(bom)
        val rows = text.trim().lines()
        assertEquals("Datum;Mitarbeiter;Art;Beginn;Ende;Minuten;Auftrag;Notiz", rows.first())
        assertTrue(rows.drop(1).any { it.contains(";Arbeit;") && it.contains(";85;Therme warten;mit Azubi") }, text)
        val tooLong = f.owner.get("/api/v1/reports/time-entries.csv?from=2020-01-01&to=$today")
        assertEquals(400, tooLong.status)
    }

    @Test
    fun `reports of two customers or unsigned reports cannot be invoiced together`() {
        val (f, report) = signed("rmix")
        val unknown = f.owner.post("/api/v1/report-invoicing/preview", mapOf("reportIds" to listOf(UUID.randomUUID().toString())))
        assertEquals(409, unknown.status)
        assertEquals("REPORT_NOT_BILLABLE", unknown.code)
        val badUnit = f.owner.put("/api/v1/report-invoicing/settings", mapOf("billingUnitMinutes" to 7, "travelMode" to "NONE"))
        assertEquals(400, badUnit.status, "only the allowed billing units")
        assertTrue(preview(f, report).path("lines").size() > 0)
    }

    private fun customerOf(f: Firm): String =
        f.owner
            .get("/api/v1/work-orders/${f.order}")
            .tree
            .path("customerId")
            .asString()
}
