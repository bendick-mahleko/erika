package de.erika.reports

import de.erika.support.ApiIntegrationTest
import de.erika.support.Phones
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestSyncDevice
import de.erika.support.TestUser
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MARA Stage 5: on-site report capture through sync (M-MO-03, M-MO-04, M-MO-06, M-MO-07,
 * M-MO-08) and the delete rules of the protocol (ADR 0038).
 */
class ReportsSyncIT : ApiIntegrationTest() {
    private val phones by lazy { Phones(this) }

    private fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    private fun id() = UUID.randomUUID().toString()

    private data class Firm(
        val owner: TestClient,
        val ownerUser: TestUser,
        val tech: TestUser,
        val device: TestSyncDevice,
        val customer: String,
    )

    private fun firm(prefix: String): Firm {
        val owner = client()
        val ownerUser = accounts.enrolled(prefix, owner, workspaceName = "Firma $prefix")
        val (phone, tech) = phones.signIn(phones.technician(owner, "$prefix-tech"))
        val customer =
            owner
                .post("/api/v1/customers", mapOf("name" to "Familie Brandt", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"), key())
                .tree
                .path("id")
                .asString()
        return Firm(owner, ownerUser, tech, TestSyncDevice(phone), customer)
    }

    private fun template(f: Firm): String =
        f.owner
            .post(
                "/api/v1/checklist-templates",
                mapOf(
                    "name" to "Wartung",
                    "items" to
                        listOf(
                            mapOf("key" to "wasser_ab", "label" to "Wasser abgestellt", "kind" to "YES_NO", "required" to true),
                            mapOf("key" to "druck", "label" to "Druck (bar)", "kind" to "VALUE", "required" to false),
                            mapOf("key" to "foto", "label" to "Foto Typenschild", "kind" to "PHOTO", "required" to true),
                        ),
                ),
                key(),
            ).tree
            .path("id")
            .asString()

    private fun workOrder(
        f: Firm,
        assignees: List<UUID>,
        templateId: String? = null,
    ): String {
        val from = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0)
        val r =
            f.owner.post(
                "/api/v1/work-orders",
                mapOf(
                    "customerId" to f.customer,
                    "title" to "Heizung warten",
                    "assigneeIds" to assignees,
                    "plannedFrom" to from.toString(),
                    "plannedTo" to from.plusHours(2).toString(),
                    "checklistTemplateId" to templateId,
                ),
                key(),
            )
        check(r.status == 201) { "work order: $r" }
        return r.tree.path("id").asString()
    }

    private fun article(f: Firm): String =
        f.owner
            .post(
                "/api/v1/articles",
                mapOf(
                    "articleNumber" to "D-15",
                    "name" to "Dichtung 1/2 Zoll",
                    "unitCode" to "C62",
                    "netPrice" to "1.20",
                    "vatRate" to "STANDARD_19",
                ),
                key(),
            ).tree
            .path("id")
            .asString()

    private fun reportId(workOrder: String) = DerivedIds.report(UUID.fromString(workOrder)).toString()

    private fun answerId(
        report: String,
        key: String,
    ) = DerivedIds.checklistAnswer(UUID.fromString(report), key).toString()

    private fun now(minusMinutes: Long = 0) =
        Instant
            .now()
            .minus(minusMinutes, ChronoUnit.MINUTES)
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()

    private fun TestSyncDevice.pushResults(): List<JsonNode> =
        push()
            .tree
            .path("results")
            .values()
            .toList()

    private fun etag(
        c: TestClient,
        path: String,
    ) = c.get(path).header("ETag")!!

    @Test
    fun `a technician records a whole report offline, the office sees it, and completion needs the mandatory items`() {
        val f = firm("bericht")
        val order = workOrder(f, listOf(f.tech.userId), template(f))
        val article = article(f)
        f.device.pullAll()
        assertNotNull(f.device.record("staff_member", f.tech.userId), "the phone knows the people for time entries")

        // Offline, in this order: report, a finished time entry, a running timer, material, Aufmaß, description.
        val report = reportId(order)
        val running = id()
        val material = id()
        val position = id()
        f.device.create("report", report, mapOf("workOrderId" to order))
        f.device.create(
            "time_entry",
            id(),
            mapOf(
                "reportId" to report,
                "technicianId" to f.tech.userId.toString(),
                "kind" to "TRAVEL",
                "startedAt" to now(90),
                "endedAt" to now(75),
            ),
        )
        f.device.create("time_entry", running, mapOf("reportId" to report, "kind" to "WORK", "startedAt" to now(70)))
        f.device.create(
            "material_line",
            material,
            mapOf(
                "reportId" to report,
                "articleId" to article,
                "description" to "Dichtung 1/2 Zoll",
                "quantity" to "2",
                "unitCode" to "C62",
            ),
        )
        f.device.create(
            "aufmass_position",
            position,
            mapOf(
                "reportId" to report,
                "label" to "Wand Bad",
                "kind" to "AREA",
                "length" to "5.40",
                "width" to "2.75",
                "deductions" to
                    listOf(
                        mapOf("label" to "Fenster", "length" to "1.20", "width" to "1.35"),
                        mapOf(
                            "length" to "0.885",
                            "width" to "2.01",
                            "count" to "2",
                        ),
                    ),
                "unitCode" to "MTK",
            ),
        )
        f.device.create("checklist_answer", answerId(report, "druck"), mapOf("reportId" to report, "itemKey" to "druck", "value" to "1,8"))
        val results = f.device.pushResults()
        assertTrue(results.all { it.path("status").asString() == "APPLIED" }, "$results")
        assertEquals("9.672", f.device.record("aufmass_position", position)!!.data["result"], "the server's result, same as the vectors")

        f.device.change("report", report, mapOf("workDescription" to "Dichtung am Heizkreis getauscht, Anlage entlüftet."))
        f.device.change("report", report, mapOf("state" to "COMPLETED"))
        val refused = f.device.pushResults()
        assertEquals("APPLIED", refused[0].path("status").asString())
        assertEquals("TIMER_RUNNING", refused[1].path("code").asString(), "$refused")

        f.device.change("time_entry", running, mapOf("endedAt" to now()))
        f.device.change("report", report, mapOf("state" to "COMPLETED"))
        assertEquals(
            "CHECKLIST_INCOMPLETE",
            f.device
                .pushResults()[1]
                .path("code")
                .asString(),
            "the mandatory yes/no item is open",
        )

        f.device.create(
            "checklist_answer",
            answerId(report, "wasser_ab"),
            mapOf(
                "reportId" to report,
                "itemKey" to "wasser_ab",
                "value" to "YES",
            ),
        )
        f.device.change("report", report, mapOf("state" to "COMPLETED"))
        assertEquals(
            "CHECKLIST_INCOMPLETE",
            f.device
                .pushResults()[1]
                .path("code")
                .asString(),
            "the photo item is open (Stage 6)",
        )

        // A photo for the item: its record is enough, the file may still be on its way.
        val photo =
            de.erika.support.TestPhotos
                .jpeg(200, 150)
        f.device.create(
            "photo",
            id(),
            de.erika.support.TestPhotos
                .record(report, photo, extra = mapOf("checklistItemKey" to "foto")),
        )
        f.device.change("report", report, mapOf("state" to "COMPLETED"))
        val done = f.device.pushResults()
        assertTrue(done.all { it.path("status").asString() == "APPLIED" }, "$done")
        assertEquals("COMPLETED", f.device.record("report", report)!!.data["state"])

        val office = f.owner.get("/api/v1/work-orders/$order/report")
        assertEquals(200, office.status, "$office")
        val tree = office.tree
        assertEquals("COMPLETED", tree.path("report").path("state").asString())
        assertEquals("Dichtung am Heizkreis getauscht, Anlage entlüftet.", tree.path("report").path("workDescription").asString())
        assertEquals(2, tree.path("timeEntries").size())
        assertEquals("TRAVEL", tree.path("timeEntries")[0].path("kind").asString())
        assertEquals("D-15", tree.path("materials")[0].path("articleNumber").asString())
        assertEquals("2", tree.path("materials")[0].path("quantity").asString())
        assertEquals("9.672", tree.path("aufmass")[0].path("result").asString())
        assertEquals(2, tree.path("aufmass")[0].path("deductions").size())
        assertEquals(listOf("YES", "1,8", ""), tree.path("checklist").values().map { it.path("value").asString("") })
    }

    @Test
    fun `without a report the office sees the open checklist, and wrong IDs and broken formulas are refused`() {
        val f = firm("bericht-leer")
        val order = workOrder(f, listOf(f.tech.userId), template(f))
        val empty = f.owner.get("/api/v1/work-orders/$order/report").tree
        assertTrue(empty.path("report").isMissingNode)
        assertEquals(3, empty.path("checklist").size())

        f.device.pullAll()
        f.device.create("report", id(), mapOf("workOrderId" to order))
        assertEquals(
            "INVALID_REPORT_ID",
            f.device
                .pushResults()[0]
                .path("code")
                .asString(),
        )

        val report = reportId(order)
        f.device.create("report", report, mapOf("workOrderId" to order))
        f.device.create(
            "aufmass_position",
            id(),
            mapOf(
                "reportId" to report,
                "label" to "Formel",
                "kind" to "FORMULA",
                "expression" to "2 *",
                "unitCode" to "MTK",
            ),
        )
        f.device.create(
            "checklist_answer",
            answerId(report, "gibtsnicht"),
            mapOf(
                "reportId" to report,
                "itemKey" to "gibtsnicht",
                "value" to "x",
            ),
        )
        f.device.create(
            "checklist_answer",
            answerId(report, "wasser_ab"),
            mapOf(
                "reportId" to report,
                "itemKey" to "wasser_ab",
                "value" to "vielleicht",
            ),
        )
        val r = f.device.pushResults()
        assertEquals(listOf("APPLIED", "REJECTED", "REJECTED", "REJECTED"), r.map { it.path("status").asString() }, "$r")
        assertEquals(listOf("INVALID_FORMULA", "CHECKLIST_ITEM_UNKNOWN", "INVALID_VALUE"), r.drop(1).map { it.path("code").asString() })
    }

    @Test
    fun `two technicians who start the same report offline share it, and the later answer wins with a note`() {
        val f = firm("bericht-zwei")
        val (phone2, tech2) = phones.signIn(phones.technician(f.owner, "zweiter"))
        val second = TestSyncDevice(phone2)
        val order = workOrder(f, listOf(f.tech.userId, tech2.userId), template(f))
        f.device.pullAll()
        second.pullAll()

        val report = reportId(order)
        val answer = answerId(report, "druck")
        for ((device, text, value) in listOf(Triple(f.device, "Erster Text", "1,5"), Triple(second, "Zweiter Text", "2,0"))) {
            device.create("report", report, mapOf("workOrderId" to order, "workDescription" to text))
            device.create("checklist_answer", answer, mapOf("reportId" to report, "itemKey" to "druck", "value" to value))
        }
        assertTrue(f.device.pushResults().all { it.path("status").asString() == "APPLIED" })
        val merged = second.pushResults()
        assertEquals(listOf("CONFLICT", "CONFLICT"), merged.map { it.path("status").asString() }, "$merged")
        assertEquals("workDescription", merged[0].path("conflicts")[0].path("field").asString())
        assertEquals("DEVICE", merged[1].path("conflicts")[0].path("winner").asString())

        val office = f.owner.get("/api/v1/work-orders/$order/report").tree
        assertEquals("Zweiter Text", office.path("report").path("workDescription").asString())
        assertEquals("2,0", office.path("checklist")[1].path("value").asString())
        assertEquals(1, TestDatabase.count("SELECT count(*) FROM report WHERE work_order_id = ?::uuid", order))
        assertEquals(
            1,
            f.owner
                .get("/api/v1/sync-conflicts?entityId=$report")
                .tree
                .path("items")
                .size(),
        )
    }

    @Test
    fun `a delete loses against a concurrent edit, and an edit brings a deleted record back`() {
        val f = firm("bericht-loeschen")
        val order = workOrder(f, listOf(f.tech.userId))
        val (phone2, _) = phones.signIn(f.tech)
        val tablet = TestSyncDevice(phone2)
        val report = reportId(order)
        val line = id()
        f.device.pullAll()
        f.device.create("report", report, mapOf("workOrderId" to order))
        f.device.create(
            "material_line",
            line,
            mapOf(
                "reportId" to report,
                "description" to "Kupferrohr 15 mm",
                "quantity" to "3",
                "unitCode" to "MTR",
            ),
        )
        f.device.pushResults()
        tablet.pullAll()

        // The tablet changes the quantity; the phone, not knowing, deletes the line: the delete loses.
        tablet.change("material_line", line, mapOf("quantity" to "4.5"))
        tablet.pushResults()
        f.device.delete("material_line", line)
        val lost = f.device.pushResults()[0]
        assertEquals("CONFLICT", lost.path("status").asString(), "$lost")
        assertEquals("deleted", lost.path("conflicts")[0].path("field").asString())
        assertEquals("SERVER", lost.path("conflicts")[0].path("winner").asString())
        assertEquals("4.5", f.device.record("material_line", line)!!.data["quantity"], "the phone has the line back")

        // Now the phone deletes with the current versions: applied. The tablet, offline, edits it: the edit wins.
        f.device.delete("material_line", line)
        assertEquals(
            "APPLIED",
            f.device
                .pushResults()[0]
                .path("status")
                .asString(),
        )
        assertNull(f.device.record("material_line", line))
        assertTrue(
            f.owner
                .get("/api/v1/work-orders/$order/report")
                .tree
                .path("materials")
                .isEmpty,
        )
        tablet.change("material_line", line, mapOf("description" to "Kupferrohr 18 mm"))
        val restored = tablet.pushResults()[0]
        assertEquals("CONFLICT", restored.path("status").asString(), "$restored")
        assertEquals("DEVICE", restored.path("conflicts")[0].path("winner").asString())
        assertEquals(
            "Kupferrohr 18 mm",
            f.owner
                .get("/api/v1/work-orders/$order/report")
                .tree
                .path("materials")[0]
                .path("description")
                .asString(),
        )
        f.device.pullAll()
        assertEquals("Kupferrohr 18 mm", f.device.record("material_line", line)!!.data["description"])

        // A record created and deleted in one push is no conflict; a deleted record leaves the other phones.
        val temp = id()
        f.device.create("time_entry", temp, mapOf("reportId" to report, "startedAt" to now(5)))
        f.device.outbox += de.erika.support.PendingMutation(id(), "time_entry", temp, "DELETE", emptyMap(), emptyMap())
        f.device.delete("material_line", line)
        assertEquals(listOf("APPLIED", "APPLIED", "APPLIED"), f.device.pushResults().map { it.path("status").asString() })
        val removals = tablet.pullAll()
        assertTrue("material_line" to line in removals, "$removals")
        assertNull(tablet.record("time_entry", temp))

        // Deleting what is already gone is fine (a resend after a lost answer with a new ID).
        f.device.outbox += de.erika.support.PendingMutation(id(), "material_line", line, "DELETE", emptyMap(), emptyMap())
        assertEquals(
            "APPLIED",
            f.device
                .pushResults()[0]
                .path("status")
                .asString(),
        )
        assertEquals(
            listOf("deleted", "restored", "deleted"),
            TestDatabase
                .strings(
                    "SELECT action FROM audit_log WHERE workspace_id = ?::uuid AND payload->>'id' = ? " +
                        "AND action IN ('reports.material_line.deleted', 'reports.material_line.restored') ORDER BY seq",
                    f.ownerUser.workspaceId.toString(),
                    line,
                ).map { it.substringAfterLast('.') },
        )
    }

    @Test
    fun `only the author changes a record, and other technicians don't see the report until it is theirs`() {
        val f = firm("bericht-umfang")
        val (phone2, tech2) = phones.signIn(phones.technician(f.owner, "kollege"))
        val colleague = TestSyncDevice(phone2)
        val order = workOrder(f, listOf(f.tech.userId))
        val report = reportId(order)
        val entry = id()
        f.device.pullAll()
        f.device.create("report", report, mapOf("workOrderId" to order))
        f.device.create("time_entry", entry, mapOf("reportId" to report, "startedAt" to now(30), "endedAt" to now()))
        f.device.pushResults()

        colleague.pullAll()
        assertNull(colleague.record("report", report))
        assertNull(colleague.record("time_entry", entry))

        // The office adds the colleague; the report and its times reach the colleague's phone.
        f.owner.put(
            "/api/v1/work-orders/$order",
            mapOf("customerId" to f.customer, "title" to "Heizung warten", "assigneeIds" to listOf(f.tech.userId, tech2.userId)),
            "If-Match" to etag(f.owner, "/api/v1/work-orders/$order"),
        )
        colleague.pullAll()
        assertNotNull(colleague.record("report", report))
        assertNotNull(colleague.record("time_entry", entry))

        // The colleague may record own times (also for others), but not change the first technician's.
        colleague.change("time_entry", entry, mapOf("note" to "korrigiert"))
        colleague.create(
            "time_entry",
            id(),
            mapOf("reportId" to report, "technicianId" to f.tech.userId.toString(), "startedAt" to now(20), "endedAt" to now(10)),
        )
        colleague.create(
            "time_entry",
            id(),
            mapOf(
                "reportId" to report,
                "technicianId" to UUID.randomUUID().toString(),
                "startedAt" to now(20),
            ),
        )
        val r = colleague.pushResults()
        assertEquals(listOf("NOT_AUTHOR", "", "TECHNICIAN_INVALID"), r.map { it.path("code").asString("") }, "$r")

        // Taken off the work order, the first technician loses report and times.
        f.owner.put(
            "/api/v1/work-orders/$order",
            mapOf("customerId" to f.customer, "title" to "Heizung warten", "assigneeIds" to listOf(tech2.userId)),
            "If-Match" to etag(f.owner, "/api/v1/work-orders/$order"),
        )
        val removals = f.device.pullAll()
        assertTrue("report" to report in removals && "time_entry" to entry in removals, "$removals")
    }

    @Test
    fun `a signed or invoiced work order's report doesn't change`() {
        val f = firm("bericht-gesperrt")
        val order = workOrder(f, listOf(f.tech.userId))
        val report = reportId(order)
        f.device.pullAll()
        f.device.create("report", report, mapOf("workOrderId" to order))
        f.device.pushResults()
        TestDatabase.update("UPDATE work_order SET status = 'SIGNED' WHERE id = ?::uuid", order)
        f.device.change("report", report, mapOf("workDescription" to "zu spät"))
        f.device.create("time_entry", id(), mapOf("reportId" to report, "startedAt" to now(5)))
        assertEquals(listOf("REPORT_LOCKED", "REPORT_LOCKED"), f.device.pushResults().map { it.path("code").asString() })
    }
}
