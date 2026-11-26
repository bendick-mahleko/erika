package de.erika.reports

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
class SignaturesIT : SignedReportTest() {
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
