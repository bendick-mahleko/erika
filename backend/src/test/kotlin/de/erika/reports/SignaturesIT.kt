package de.erika.reports

import de.erika.support.ApiIntegrationTest
import de.erika.support.Phones
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestInfrastructure
import de.erika.support.TestPhotos
import de.erika.support.TestSyncDevice
import de.erika.support.TestTus
import de.erika.support.TestUser
import de.erika.support.WorkerContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MARA Stage 7: signature, immutability and the report PDF (M-MO-09 to M-MO-11, M-BR-01 to
 * M-BR-03, M-BR-08). The test phone builds the canonical report from its own synced records,
 * as the app does, so a hash computed on the device must be accepted by the server.
 */
class SignaturesIT : ApiIntegrationTest() {
    private val phones by lazy { Phones(this) }

    private fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    private fun id() = UUID.randomUUID().toString()

    private data class Firm(
        val owner: TestClient,
        val ownerUser: TestUser,
        val tech: TestUser,
        val device: TestSyncDevice,
        val order: String,
    ) {
        fun report(version: Int = 1) = DerivedIds.report(UUID.fromString(order), version).toString()
    }

    private fun firm(prefix: String): Firm {
        val owner = client()
        val ownerUser = accounts.enrolled(prefix, owner, workspaceName = "Sanitär $prefix")
        val (phone, tech) = phones.signIn(phones.technician(owner, "$prefix-tech"))
        val customer =
            owner
                .post(
                    "/api/v1/customers",
                    mapOf(
                        "name" to "Familie Sommer",
                        "countryCode" to "DE",
                        "vatTreatment" to "DOMESTIC",
                        "email" to "sommer-$prefix@example.test",
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()
        val template =
            owner
                .post(
                    "/api/v1/checklist-templates",
                    mapOf(
                        "name" to "Wartung",
                        "items" to listOf(mapOf("key" to "druck", "label" to "Druck (bar)", "kind" to "VALUE", "required" to true)),
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()
        val order =
            owner
                .post(
                    "/api/v1/work-orders",
                    mapOf(
                        "customerId" to customer,
                        "title" to "Therme warten",
                        "assigneeIds" to listOf(tech.userId),
                        "checklistTemplateId" to template,
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()
        val device = TestSyncDevice(phone)
        device.pullAll()
        return Firm(owner, ownerUser, tech, device, order)
    }

    private fun TestSyncDevice.pushResults(): List<JsonNode> =
        push()
            .tree
            .path("results")
            .values()
            .toList()

    private fun now(minusMinutes: Long = 0) =
        Instant
            .now()
            .minus(minusMinutes, ChronoUnit.MINUTES)
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()

    /** Records a small report with a stored photo; returns the photo's ID. */
    private fun record(
        f: Firm,
        report: String = f.report(),
    ): String {
        f.device.create("report", report, mapOf("workOrderId" to f.order, "workDescription" to "Brenner gereinigt, Druck geprüft."))
        f.device.create(
            "time_entry",
            id(),
            mapOf("reportId" to report, "startedAt" to now(95), "endedAt" to now(10), "note" to "mit Azubi"),
        )
        f.device.create(
            "material_line",
            id(),
            mapOf(
                "reportId" to report,
                "description" to "Dichtung",
                "quantity" to "2.000",
                "unitCode" to "C62",
            ),
        )
        f.device.create(
            "aufmass_position",
            id(),
            mapOf("reportId" to report, "label" to "Wand", "kind" to "AREA", "length" to "5.40", "width" to "2.75", "unitCode" to "MTK"),
        )
        f.device.create(
            "checklist_answer",
            DerivedIds.checklistAnswer(UUID.fromString(report), "druck").toString(),
            mapOf("reportId" to report, "itemKey" to "druck", "value" to "1,8"),
        )
        val jpeg = TestPhotos.jpeg(640, 480, seed = 9)
        val photo = id()
        f.device.create("photo", photo, TestPhotos.record(report, jpeg))
        assertTrue(f.device.pushResults().all { it.path("status").asString() == "APPLIED" })
        assertEquals(204, TestTus(f.device.phone).upload(photo, jpeg, 64 * 1024))
        WorkerContext.drain()
        f.device.pullAll()
        return photo
    }

    /** The canonical report input built from the phone's records, as the app does. */
    private fun phoneRaw(
        f: Firm,
        report: String,
    ): Map<String, Any?> {
        val d = f.device
        val r = d.record("report", report)!!.data
        val wo = d.record("work_order", f.order)!!.data
        val names = d.all("staff_member").associate { it.id to it.data["displayName"] }
        val items = d.record("checklist_template", wo["checklistTemplateId"] as String)!!.data["items"] as List<*>
        val mine = { entity: String -> d.all(entity).filter { it.data["reportId"] == report } }
        val answers = mine("checklist_answer").associate { it.data["itemKey"] to it.data["value"] }
        return mapOf(
            "format" to 1,
            "reportId" to report,
            "version" to r["version"],
            "workOrder" to mapOf("id" to f.order, "title" to wo["title"]),
            "customer" to mapOf("name" to d.record("customer", wo["customerId"] as String)!!.data["name"]),
            "workDescription" to r["workDescription"],
            "times" to
                mine("time_entry").map {
                    mapOf(
                        "id" to it.id,
                        "technician" to names[it.data["technicianId"]],
                        "kind" to it.data["kind"],
                        "start" to it.data["startedAt"],
                        "end" to it.data["endedAt"],
                        "note" to it.data["note"],
                    )
                },
            "materials" to
                mine("material_line").map {
                    mapOf(
                        "id" to it.id,
                        "description" to it.data["description"],
                        "quantity" to it.data["quantity"],
                        "unit" to it.data["unitCode"],
                    )
                },
            "aufmass" to
                mine("aufmass_position").map {
                    it.data.filterKeys { k ->
                        k in
                            setOf("label", "kind", "length", "width", "height", "count", "expression", "deductions", "result")
                    } +
                        mapOf("id" to it.id, "unit" to it.data["unitCode"])
                },
            "checklist" to
                items.map { it as Map<*, *> }.filter { it["kind"] != "PHOTO" }.map {
                    mapOf("key" to it["key"], "label" to it["label"], "value" to answers[it["key"]])
                },
            "photos" to
                mine("photo").map {
                    mapOf(
                        "id" to it.id,
                        "category" to it.data["category"],
                        "caption" to it.data["caption"],
                        "checklistItemKey" to it.data["checklistItemKey"],
                        "sha256" to it.data["sha256"],
                    )
                },
        )
    }

    private fun png(): String {
        val image = BufferedImage(300, 100, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            color = java.awt.Color.BLACK
            drawLine(10, 80, 290, 20)
            dispose()
        }
        return Base64.getEncoder().encodeToString(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
    }

    private fun code() = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".random() }.joinToString("")

    private val downloadKey = "k" + UUID.randomUUID().toString().replace("-", "") + "x"

    private fun sign(
        f: Firm,
        report: String,
        hash: String = ReportCanonical.hash(phoneRaw(f, report)),
        code: String = code(),
        kind: String = "SIGNED",
    ): JsonNode {
        f.device.create(
            "report_signature",
            DerivedIds.of(UUID.fromString(report), "signature").toString(),
            mapOf(
                "reportId" to report,
                "kind" to kind,
                "contentHash" to hash,
                "signerName" to if (kind == "SIGNED") "Maria Sommer" else null,
                "signedAt" to now(),
                "comment" to "Alles sauber",
                "notSignedReason" to if (kind == "NOT_SIGNED") "Kundin nicht anwesend" else null,
                "strokes" to listOf(listOf(listOf(10, 80), listOf(290, 20))),
                "signaturePng" to if (kind == "SIGNED") png() else null,
                "verificationCode" to code,
                "downloadKey" to downloadKey,
                "customerEmail" to "sommer@example.test",
            ),
        )
        return f.device.pushResults().single()
    }

    private fun mailsTo(email: String): Int {
        val query = URLEncoder.encode("to:$email", Charsets.UTF_8)
        val response =
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${TestInfrastructure.mailpitApi}/api/v1/search?query=$query")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return Regex("\"messages_count\":(\\d+)")
            .find(response.body())
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 0
    }

    @Test
    fun `the customer signs what the phone shows, and the version is locked, archived as PDF and emailed`() {
        val f = firm("sig")
        val photo = record(f)
        val report = f.report()
        val code = code()
        val mailsBefore = mailsTo("sommer@example.test")
        val result = sign(f, report, code = code)
        assertEquals("APPLIED", result.path("status").asString(), "the phone's hash is the server's: $result")
        assertEquals(
            "SIGNED",
            f.owner
                .get("/api/v1/work-orders/${f.order}")
                .tree
                .path("status")
                .asString(),
        )
        f.device.pullAll()
        assertEquals("SIGNED", f.device.record("report", report)!!.data["state"])

        // Locked: through sync ...
        val entry = f.device.all("time_entry").first { it.data["reportId"] == report }
        f.device.change("time_entry", entry.id, mapOf("note" to "nachträglich"))
        f.device.create(
            "material_line",
            id(),
            mapOf("reportId" to report, "description" to "Extra", "quantity" to "1", "unitCode" to "C62"),
        )
        f.device.change("photo", photo, mapOf("caption" to "anders"))
        assertEquals(listOf("REPORT_LOCKED", "REPORT_LOCKED", "REPORT_LOCKED"), f.device.pushResults().map { it.path("code").asString() })
        // ... and even through SQL as the app role (the trigger).
        val ws = f.ownerUser.workspaceId.toString()
        for (sql in listOf(
            "UPDATE report SET work_description = 'geändert' WHERE id = '$report'",
            "UPDATE time_entry SET note = 'geändert' WHERE id = '${entry.id}'",
            "UPDATE photo SET caption = 'geändert' WHERE id = '$photo'",
            "UPDATE report_signature SET signer_name = 'Jemand' WHERE report_id = '$report'",
            "DELETE FROM time_entry WHERE id = '${entry.id}'",
        )) {
            val error =
                runCatching {
                    TestDatabase.asApp { c ->
                        c.autoCommit = false
                        c.createStatement().use { st ->
                            st.execute("SELECT set_config('app.workspace_id', '$ws', true)")
                            st.executeUpdate(sql)
                        }
                        c.rollback()
                    }
                }.exceptionOrNull()
            assertNotNull(error, "refused: $sql")
        }

        // The worker archives the PDF and emails it; every send is logged.
        WorkerContext.drain()
        val summary =
            f.owner
                .get("/api/v1/work-orders/${f.order}/report")
                .tree
                .path("report")
        assertEquals("SIGNED", summary.path("state").asString())
        assertEquals("Maria Sommer", summary.path("signature").path("signerName").asString())
        assertTrue(summary.path("signature").path("pdfAvailable").asBoolean())
        assertTrue(summary.path("signature").path("emailed").asBoolean())
        assertEquals(mailsBefore + 1, mailsTo("sommer@example.test"))
        val pdf = f.owner.getBytes("/api/v1/reports/$report/pdf")
        assertEquals(200, pdf.first)
        val text = Loader.loadPDF(pdf.second).use { PDFTextStripper().getText(it) }
        assertTrue("Therme warten" in text && "Maria Sommer" in text && code in text, text)
        assertTrue(summary.path("signature").path("contentHash").asString() in text.replace("\n", ""), "the hash is on the PDF")
        assertEquals(1, TestDatabase.count("SELECT count(*) FROM report_delivery WHERE report_id = ?::uuid", report))
        assertEquals(
            3,
            TestDatabase.count(
                "SELECT count(*) FROM stored_object WHERE workspace_id = ?::uuid AND media_type IN ('application/pdf', 'image/jpeg', 'message/rfc822')",
                ws,
            ),
            "PDF, photo and message in the archive",
        )
        val photoYears =
            TestDatabase
                .strings(
                    "SELECT retention_until FROM stored_object WHERE workspace_id = ?::uuid AND media_type = 'image/jpeg'",
                    ws,
                ).single()
        assertEquals(
            java.time.LocalDate
                .now()
                .plusYears(5)
                .year,
            java.time.LocalDate
                .parse(photoYears)
                .year,
            "photos: 5 years (ADR 0040)",
        )
        WorkerContext.drain()
        assertEquals(1, TestDatabase.count("SELECT count(*) FROM report_delivery WHERE report_id = ?::uuid", report), "sent once")

        // The public check: hash and status without a session, the PDF only with the key.
        val anonymous = client()
        val verification = anonymous.get("/api/v1/public/report-verifications/$code")
        assertEquals(200, verification.status, "$verification")
        assertEquals("SIGNED", verification.tree.path("kind").asString())
        assertEquals(summary.path("signature").path("contentHash").asString(), verification.tree.path("contentHash").asString())
        assertFalse(verification.body.contains("Therme"), "no content")
        assertEquals(200, anonymous.getBytes("/api/v1/public/report-verifications/$code/pdf?key=$downloadKey").first)
        assertEquals(404, anonymous.getBytes("/api/v1/public/report-verifications/$code/pdf?key=${downloadKey.reversed()}").first)
        assertEquals(404, anonymous.get("/api/v1/public/report-verifications/AAAAAAAAAA").status)
        assertTrue(
            TestDatabase
                .strings("SELECT action FROM audit_log WHERE workspace_id = ?::uuid ORDER BY seq", ws)
                .containsAll(
                    listOf(
                        "reports.report.signed",
                        "reports.report_signature.created",
                        "reports.report.pdf_archived",
                        "reports.report.sent",
                    ),
                ),
        )
    }

    @Test
    fun `a signature over different content is refused`() {
        val f = firm("sig-falsch")
        record(f)
        val raw = phoneRaw(f, f.report()).toMutableMap().apply { put("workDescription", "etwas anderes") }
        val result = sign(f, f.report(), hash = ReportCanonical.hash(raw))
        assertEquals("HASH_MISMATCH", result.path("code").asString(), "$result")
        assertEquals(
            "OPEN",
            f.owner
                .get("/api/v1/work-orders/${f.order}/report")
                .tree
                .path("report")
                .path("state")
                .asString(),
        )
    }

    @Test
    fun `not signed with a reason, then a correction is version 2, signed again, with a new PDF`() {
        val f = firm("sig-v2")
        record(f)
        val v1 = f.report()
        val first = code()
        assertEquals("APPLIED", sign(f, v1, code = first, kind = "NOT_SIGNED").path("status").asString())
        assertEquals(
            "PLANNED",
            f.owner
                .get("/api/v1/work-orders/${f.order}")
                .tree
                .path("status")
                .asString(),
            "an unsigned report doesn't set SIGNED",
        )
        WorkerContext.drain()

        // The correction starts as a copy of version 1.
        val v2 = f.report(2)
        f.device.create("report", v2, mapOf("workOrderId" to f.order, "version" to 2))
        assertEquals(
            "APPLIED",
            f.device
                .pushResults()
                .single()
                .path("status")
                .asString(),
        )
        f.device.pullAll()
        for (entity in listOf("time_entry", "material_line", "aufmass_position", "checklist_answer", "photo")) {
            assertEquals(1, f.device.all(entity).count { it.data["reportId"] == v2 }, "$entity copied into version 2")
        }
        assertEquals(
            "STORED",
            f.device
                .all("photo")
                .single { it.data["reportId"] == v2 }
                .data["state"],
            "the copy shares the stored file",
        )
        val line = f.device.all("material_line").single { it.data["reportId"] == v2 }
        f.device.change("material_line", line.id, mapOf("quantity" to "3"))
        assertEquals(
            "APPLIED",
            f.device
                .pushResults()
                .single()
                .path("status")
                .asString(),
        )
        f.device.pullAll()

        val second = code()
        assertEquals("APPLIED", sign(f, v2, code = second).path("status").asString())
        WorkerContext.drain()
        assertEquals(
            "SIGNED",
            f.owner
                .get("/api/v1/work-orders/${f.order}")
                .tree
                .path("status")
                .asString(),
        )
        val versions =
            f.owner
                .get("/api/v1/work-orders/${f.order}/report")
                .tree
                .path("versions")
        assertEquals(listOf("NOT_SIGNED", "SIGNED"), versions.values().map { it.path("state").asString() })
        assertTrue(
            client()
                .get("/api/v1/public/report-verifications/$first")
                .tree
                .path("superseded")
                .asBoolean(),
            "version 1 is superseded",
        )
        assertEquals(200, f.owner.getBytes("/api/v1/reports/$v2/pdf").first)
        assertEquals(
            2,
            TestDatabase.count("SELECT count(*) FROM report_delivery WHERE report_id IN (?::uuid, ?::uuid)", v1, v2),
            "each version emailed",
        )

        // A third version needs the second to be signed first; the first can't be corrected twice.
        f.device.create("report", f.report(3), mapOf("workOrderId" to f.order, "version" to 3))
        f.device.create("report", f.report(5), mapOf("workOrderId" to f.order, "version" to 5))
        assertEquals(listOf("APPLIED", "REJECTED"), f.device.pushResults().map { it.path("status").asString() })
    }
}
