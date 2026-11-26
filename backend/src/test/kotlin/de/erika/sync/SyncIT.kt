package de.erika.sync

import de.erika.support.ApiIntegrationTest
import de.erika.support.Mails
import de.erika.support.Phones
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestSyncDevice
import de.erika.support.TestUser
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MARA Stage 4: the sync protocol (ADR 0038, M-MO-01, M-MO-02, M-NF-01 to M-NF-03). */
class SyncIT : ApiIntegrationTest() {
    private val phones by lazy { Phones(this) }

    private fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    private fun customer(
        c: TestClient,
        name: String,
    ): String =
        c
            .post(
                "/api/v1/customers",
                mapOf("name" to name, "countryCode" to "DE", "vatTreatment" to "DOMESTIC"),
                key(),
            ).tree
            .path("id")
            .asString()

    private fun workOrder(
        c: TestClient,
        customerId: String,
        title: String,
        assignees: List<UUID>,
        plannedFrom: OffsetDateTime? = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withNano(0),
    ): String {
        val r =
            c.post(
                "/api/v1/work-orders",
                mapOf(
                    "customerId" to customerId,
                    "title" to title,
                    "assigneeIds" to assignees,
                    "plannedFrom" to plannedFrom?.toString(),
                    "plannedTo" to plannedFrom?.plusHours(2)?.toString(),
                ),
                key(),
            )
        check(r.status == 201) { "work order: $r" }
        return r.tree.path("id").asString()
    }

    private data class Firm(
        val owner: TestClient,
        val ownerUser: TestUser,
        val tech: TestUser,
        val device: TestSyncDevice,
    )

    private fun firm(prefix: String): Firm {
        val owner = client()
        val ownerUser = accounts.enrolled(prefix, owner, workspaceName = "Firma $prefix")
        val (phone, tech) = phones.signIn(phones.technician(owner, "$prefix-tech"))
        return Firm(owner, ownerUser, tech, TestSyncDevice(phone))
    }

    private fun etag(
        c: TestClient,
        path: String,
    ) = c.get(path).header("ETag")!!

    @Test
    fun `an office work order reaches the phone, and the phone's status and notes reach the office`() {
        val f = firm("sync-basis")
        val customer = customer(f.owner, "Familie Keller")
        val order = workOrder(f.owner, customer, "Heizung warten", listOf(f.tech.userId))
        workOrder(f.owner, customer, "Nicht meiner", emptyList())

        f.device.pullAll()
        val local = assertNotNull(f.device.record("work_order", order))
        assertEquals("Heizung warten", local.data["title"])
        assertEquals(1, f.device.all("work_order").size, "only the technician's own work orders")
        assertNotNull(f.device.record("customer", customer), "customers: the whole workspace")

        f.device.change("work_order", order, mapOf("status" to "IN_PROGRESS", "workNotes" to "Kessel entkalkt"))
        val pushed = f.device.push()
        assertEquals(
            "APPLIED",
            pushed.tree
                .path("results")[0]
                .path("status")
                .asString(),
            "$pushed",
        )
        val office = f.owner.get("/api/v1/work-orders/$order").tree
        assertEquals("IN_PROGRESS", office.path("status").asString())
        assertEquals("Kessel entkalkt", office.path("workNotes").asString())
        assertNotNull(f.device.record("work_order", order)!!.fieldVersions["status"])

        // The office changes the plan; the phone gets it on the next pull.
        f.owner.put(
            "/api/v1/work-orders/$order",
            mapOf("customerId" to customer, "title" to "Heizung warten und Filter tauschen", "assigneeIds" to listOf(f.tech.userId)),
            "If-Match" to etag(f.owner, "/api/v1/work-orders/$order"),
        )
        f.device.pullAll()
        assertEquals("Heizung warten und Filter tauschen", f.device.record("work_order", order)!!.data["title"])
        assertEquals("IN_PROGRESS", f.device.record("work_order", order)!!.data["status"], "the office kept the status")
        assertNotNull(
            TestDatabase.asOwner { c ->
                c
                    .prepareStatement("SELECT last_sync_at FROM mobile_device WHERE id = ?::uuid")
                    .apply { setString(1, f.device.phone.deviceId) }
                    .executeQuery()
                    .run {
                        next()
                        getObject(1)
                    }
            },
        )
    }

    @Test
    fun `a work order assigned to someone else leaves the phone`() {
        val f = firm("sync-umplanung")
        val (_, other) = phones.signIn(phones.technician(f.owner, "anderer"))
        val customer = customer(f.owner, "Wohnbau Petersen")
        val order = workOrder(f.owner, customer, "Bad fliesen", listOf(f.tech.userId))
        f.device.pullAll()
        assertNotNull(f.device.record("work_order", order))

        f.owner.put(
            "/api/v1/work-orders/$order",
            mapOf("customerId" to customer, "title" to "Bad fliesen", "assigneeIds" to listOf(other.userId)),
            "If-Match" to etag(f.owner, "/api/v1/work-orders/$order"),
        )
        val removals = f.device.pullAll()
        assertTrue("work_order" to order in removals, "$removals")
        assertNull(f.device.record("work_order", order))

        // Writing to it now is refused.
        f.device.outbox +=
            de.erika.support.PendingMutation(
                UUID.randomUUID().toString(),
                "work_order",
                order,
                "UPDATE",
                mapOf("status" to "DONE"),
                mapOf("status" to 1L),
            )
        assertEquals(
            "NOT_IN_SCOPE",
            f.device
                .push()
                .tree
                .path("results")[0]
                .path("code")
                .asString(),
        )
    }

    @Test
    fun `concurrent changes follow the field rules and leave a note for the office`() {
        val f = firm("sync-konflikt")
        val customer = customer(f.owner, "Bäckerei Wolf")
        val order = workOrder(f.owner, customer, "Lüftung prüfen", listOf(f.tech.userId))
        f.device.pullAll()

        // Meanwhile the office sets the status and renames the work order.
        f.owner.put(
            "/api/v1/work-orders/$order",
            mapOf(
                "customerId" to customer,
                "title" to "Lüftung prüfen (Halle 2)",
                "assigneeIds" to listOf(f.tech.userId),
                "status" to "CANCELLED",
            ),
            "If-Match" to etag(f.owner, "/api/v1/work-orders/$order"),
        )
        // The technician, offline, set the work order to done: the technician wins the status.
        f.device.change("work_order", order, mapOf("status" to "DONE"))
        val result =
            f.device
                .push()
                .tree
                .path("results")[0]
        assertEquals("CONFLICT", result.path("status").asString(), "$result")
        assertEquals("DEVICE", result.path("conflicts")[0].path("winner").asString())
        assertEquals("CANCELLED", result.path("conflicts")[0].path("serverValue").asString())
        assertEquals(
            "DONE",
            result
                .path("record")
                .path("data")
                .path("status")
                .asString(),
        )
        assertEquals(
            "Lüftung prüfen (Halle 2)",
            result
                .path("record")
                .path("data")
                .path("title")
                .asString(),
            "a different field is no conflict",
        )

        val notes = f.owner.get("/api/v1/sync-conflicts?entityId=$order")
        assertEquals(200, notes.status, "$notes")
        val note = notes.tree.path("items")[0]
        assertEquals("status", note.path("field").asString())
        assertEquals("\"CANCELLED\"", note.path("serverValue").asString())
        assertEquals("DEVICE", note.path("winner").asString())
        assertEquals("Test sync-konflikt-tech", note.path("displayName").asString())

        // An owner on the phone changes the title the office changed meanwhile: the office wins.
        val (ownerPhone, _) = phones.signIn(f.ownerUser)
        val ownerDevice = TestSyncDevice(ownerPhone)
        ownerDevice.pullAll()
        f.owner.put(
            "/api/v1/work-orders/$order",
            mapOf("customerId" to customer, "title" to "Lüftung Halle 2", "assigneeIds" to listOf(f.tech.userId), "status" to "DONE"),
            "If-Match" to etag(f.owner, "/api/v1/work-orders/$order"),
        )
        ownerDevice.change("work_order", order, mapOf("title" to "Lüftung (vom Handy)"))
        val planning = ownerDevice.push().tree.path("results")[0]
        assertEquals("CONFLICT", planning.path("status").asString())
        assertEquals("SERVER", planning.path("conflicts")[0].path("winner").asString())
        assertEquals(
            "Lüftung Halle 2",
            f.owner
                .get("/api/v1/work-orders/$order")
                .tree
                .path("title")
                .asString(),
        )

        // Customer data: the later write wins, with a note.
        f.device.pullAll()
        f.owner.put(
            "/api/v1/customers/$customer",
            mapOf("name" to "Bäckerei Wolf", "countryCode" to "DE", "vatTreatment" to "DOMESTIC", "city" to "Halle"),
            "If-Match" to etag(f.owner, "/api/v1/customers/$customer"),
        )
        f.device.change("customer", customer, mapOf("city" to "Halle (Saale)"))
        val lww =
            f.device
                .push()
                .tree
                .path("results")[0]
        assertEquals("CONFLICT", lww.path("status").asString())
        assertEquals("DEVICE", lww.path("conflicts")[0].path("winner").asString())
        assertEquals(
            "Halle (Saale)",
            f.owner
                .get("/api/v1/customers/$customer")
                .tree
                .path("city")
                .asString(),
        )
        assertEquals(
            1,
            f.owner
                .get("/api/v1/sync-conflicts?entityId=$customer")
                .tree
                .path("items")
                .size(),
        )
    }

    @Test
    fun `a resent mutation changes nothing and gets the same answer`() {
        val f = firm("sync-doppelt")
        val customer = customer(f.owner, "Kita Sonnenschein")
        val order = workOrder(f.owner, customer, "Wasserhahn", listOf(f.tech.userId))
        f.device.pullAll()
        val m = f.device.change("work_order", order, mapOf("workNotes" to "Dichtung getauscht"))
        val first =
            f.device
                .push()
                .tree
                .path("results")[0]
        f.device.outbox += m
        val second =
            f.device
                .push()
                .tree
                .path("results")[0]
        assertEquals(first, second, "same answer (key order aside)")
        val changes =
            TestDatabase.asOwner { c ->
                c
                    .prepareStatement("SELECT count(*) FROM audit_log WHERE workspace_id = ? AND action = 'workorders.work_order.changed'")
                    .apply { setObject(1, f.ownerUser.workspaceId) }
                    .executeQuery()
                    .run {
                        next()
                        getInt(1)
                    }
            }
        assertEquals(1, changes, "applied once")
    }

    @Test
    fun `on site a technician creates a customer and a work order, and the office is told`() {
        val f = firm("sync-vorort")
        val customerId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        f.device.create(
            "customer",
            customerId,
            mapOf(
                "name" to "Herr Neumann",
                "street" to "Gartenweg 3",
                "postalCode" to "04109",
                "city" to "Leipzig",
            ),
        )
        f.device.create(
            "work_order",
            orderId,
            mapOf(
                "customerId" to customerId.toString(),
                "title" to "Rohrbruch Keller",
                "status" to "IN_PROGRESS",
            ),
        )
        val results =
            f.device
                .push()
                .tree
                .path("results")
        assertEquals(listOf("APPLIED", "APPLIED"), results.values().map { it.path("status").asString() }, "$results")

        val office = f.owner.get("/api/v1/work-orders/$orderId").tree
        assertEquals("Rohrbruch Keller", office.path("title").asString())
        assertEquals(listOf(f.tech.userId.toString()), office.path("assigneeIds").values().map { it.asString() })
        assertEquals(
            "PRIVATE",
            f.owner
                .get("/api/v1/customers/$customerId")
                .tree
                .path("customerType")
                .asString(),
        )
        val mail = Mails.queuedTo(jdbc, f.ownerUser.email).single { it.getValue("subject").contains("MARA") }
        assertTrue(mail.getValue("subject").contains("Rohrbruch Keller"), "$mail")
        assertTrue(mail.getValue("body").contains("Herr Neumann"), "$mail")

        // A resend of the create (lost answer) is answered with the record, not a second one.
        f.device.create("work_order", orderId, mapOf("customerId" to customerId.toString(), "title" to "Rohrbruch Keller"))
        assertEquals(
            "APPLIED",
            f.device
                .push()
                .tree
                .path("results")[0]
                .path("status")
                .asString(),
        )
    }

    @Test
    fun `rule violations are rejected with a code and change nothing`() {
        val f = firm("sync-regeln")
        val customer = customer(f.owner, "Autohaus Lange")
        val order = workOrder(f.owner, customer, "Rolltor", listOf(f.tech.userId))
        f.device.pullAll()

        val other = firm("sync-fremd")
        val foreign = workOrder(other.owner, customer(other.owner, "Fremd"), "Fremder Auftrag", listOf(other.tech.userId))

        f.device.change("work_order", order, mapOf("status" to "SIGNED"))
        f.device.change("work_order", order, mapOf("title" to "Technician renames"))
        f.device.change("work_order", order, mapOf("status" to "NONSENSE"))
        f.device.outbox +=
            listOf(
                de.erika.support.PendingMutation(
                    UUID.randomUUID().toString(),
                    "work_order",
                    foreign,
                    "UPDATE",
                    mapOf("status" to "DONE"),
                    mapOf("status" to 1L),
                ),
                de.erika.support.PendingMutation(UUID.randomUUID().toString(), "invoice", order, "UPDATE", mapOf("x" to 1), emptyMap()),
                de.erika.support.PendingMutation(
                    UUID.randomUUID().toString(),
                    "article",
                    UUID.randomUUID().toString(),
                    "CREATE",
                    emptyMap(),
                    emptyMap(),
                ),
            )
        val codes =
            f.device
                .push()
                .tree
                .path("results")
                .values()
                .map { it.path("code").asString() }
        assertEquals(
            listOf("STATUS_NOT_ALLOWED", "FIELD_NOT_ALLOWED", "INVALID_STATUS", "NOT_FOUND", "UNKNOWN_ENTITY", "CREATE_NOT_ALLOWED"),
            codes,
        )
        assertEquals(
            "PLANNED",
            f.owner
                .get("/api/v1/work-orders/$order")
                .tree
                .path("status")
                .asString(),
        )
        assertEquals(
            "PLANNED",
            other.owner
                .get("/api/v1/work-orders/$foreign")
                .tree
                .path("status")
                .asString(),
            "tenant B untouched",
        )

        val old = f.device.phone.post("/api/v1/mobile/sync/push", mapOf("protocolVersion" to 99, "mutations" to emptyList<Any>()))
        assertEquals(426, old.status)
        assertEquals("PROTOCOL_UNSUPPORTED", old.code)
        assertEquals(
            400,
            f.device.phone
                .get("/api/v1/mobile/sync/pull?protocolVersion=0")
                .status,
            "below the contract's minimum",
        )
        assertEquals(
            "PROTOCOL_UNSUPPORTED",
            f.device.phone
                .get("/api/v1/mobile/sync/pull?protocolVersion=2")
                .code,
        )
    }

    @Test
    fun `prices reach a technician's phone only when the owner allows it`() {
        val f = firm("sync-preise")
        val article =
            f.owner
                .post(
                    "/api/v1/articles",
                    mapOf(
                        "name" to "Thermostatkopf",
                        "unitCode" to "C62",
                        "netPrice" to "24.90",
                        "vatRate" to "STANDARD_19",
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()
        f.device.pullAll()
        assertNull(f.device.record("article", article)!!.data["netPrice"])
        assertEquals("Thermostatkopf", f.device.record("article", article)!!.data["name"])

        val memberId =
            f.owner
                .get("/api/v1/staff")
                .tree
                .path("items")
                .values()
                .single { it.path("userId").asString() == f.tech.userId.toString() }
                .path("memberId")
                .asString()
        assertEquals(200, f.owner.put("/api/v1/staff/$memberId", mapOf("canSeePrices" to true), "If-Match" to "\"0\"").status)
        f.device.pullAll()
        assertEquals("24.90", f.device.record("article", article)!!.data["netPrice"])
    }

    @Test
    fun `records that existed before the first sync arrive with the first pull`() {
        val owner = client()
        val ownerUser = accounts.enrolled("sync-alt", owner)
        val customer = customer(owner, "Altkunde")
        // A work order written before MARA's change feed existed (as after the V14 migration).
        val legacy = UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO work_order (id, workspace_id, customer_id, title, created_by) VALUES (?, ?, ?::uuid, 'Alter Auftrag', ?)",
            legacy,
            ownerUser.workspaceId,
            customer,
            ownerUser.userId,
        )
        val (phone, _) = phones.signIn(ownerUser)
        val device = TestSyncDevice(phone)
        device.pullAll()
        assertEquals("Alter Auftrag", device.record("work_order", legacy)?.data?.get("title"))
    }

    @Test
    fun `the push token is stored and a change queues a sync message`() {
        val f = firm("sync-push")
        assertEquals(
            204,
            f.device.phone
                .send("PUT", "/api/v1/mobile/devices/me/push-token", mapOf("token" to "fcm-token-123"))
                .status,
        )
        val stored =
            TestDatabase.asOwner { c ->
                c
                    .prepareStatement(
                        "SELECT push_token FROM mobile_device WHERE id = ?::uuid",
                    ).apply { setString(1, f.device.phone.deviceId) }
                    .executeQuery()
                    .run {
                        next()
                        getString(1)
                    }
            }
        assertEquals("fcm-token-123", stored)
        customer(f.owner, "Neukunde")
        val jobs =
            jdbc
                .sql("SELECT count(*) FROM job WHERE type = 'sync.notify-devices' AND workspace_id = ?")
                .param(f.ownerUser.workspaceId)
                .query(Int::class.java)
                .single()
        assertTrue(jobs >= 1)
    }
}
