package de.erika.sync

import de.erika.support.ApiIntegrationTest
import de.erika.support.MobileClient
import de.erika.support.PendingMutation
import de.erika.support.Phones
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestSyncDevice
import de.erika.support.TestUser
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-based fault injection for the sync protocol (ADR 0038, M-NF-01 to M-NF-03): random
 * sequences of local changes, office edits, pushes whose answer is lost after the commit,
 * pushes that never arrive, duplicate pushes, replayed (out-of-order) pulls and records made
 * on site. After every run, nothing is lost or duplicated and phone and server agree.
 *
 * `SYNC_FAULT_RUNS` sets the number of runs (every build: 20; nightly: 10,000) and
 * `SYNC_FAULT_SEED` repeats a failed run. Clock skew can't matter: the protocol never uses the
 * phone's clock (versions are the server's sequence numbers).
 */
class SyncFaultIT : ApiIntegrationTest() {
    private val phones by lazy { Phones(this) }
    private val runs = System.getenv("SYNC_FAULT_RUNS")?.toIntOrNull() ?: 20
    private val steps = 25

    private fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    /** What the server must hold, from the test's own bookkeeping. */
    private data class Expected(
        var title: String,
        var status: String,
        var notes: String?,
    )

    private class Firm(
        val owner: TestClient,
        val ownerUser: TestUser,
        val tech: TestUser,
        val phone: MobileClient,
        val customer: String,
    )

    private val firm by lazy {
        val owner = client()
        val ownerUser = accounts.enrolled("fehler", owner, workspaceName = "Fehlerfirma")
        val (phone, tech) = phones.signIn(phones.technician(owner, "fehler-tech"))
        val customer =
            owner
                .post("/api/v1/customers", mapOf("name" to "Stammkunde", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"), key())
                .tree
                .path("id")
                .asString()
        Firm(owner, ownerUser, tech, phone, customer)
    }

    @Test
    fun `random faults lose nothing and duplicate nothing`() {
        val seed = System.getenv("SYNC_FAULT_SEED")?.toLongOrNull() ?: System.nanoTime()
        val device = TestSyncDevice(firm.phone)
        device.pullAll()
        repeat(runs) { run ->
            val runSeed = seed + run
            try {
                runOnce(Random(runSeed), device)
            } catch (e: Throwable) {
                throw AssertionError("sync fault run failed; repeat with SYNC_FAULT_SEED=$runSeed SYNC_FAULT_RUNS=1", e)
            }
        }
    }

    private fun runOnce(
        random: Random,
        device: TestSyncDevice,
    ) {
        val f = firm
        val expected = linkedMapOf<String, Expected>()
        repeat(3) { i ->
            val title = "Auftrag ${UUID.randomUUID().toString().take(6)} $i"
            val id =
                f.owner
                    .post(
                        "/api/v1/work-orders",
                        mapOf(
                            "customerId" to f.customer,
                            "title" to title,
                            "assigneeIds" to listOf(f.tech.userId),
                        ),
                        key(),
                    ).tree
                    .path("id")
                    .asString()
            expected[id] = Expected(title, "PLANNED", null)
        }
        val createdCustomers = mutableMapOf<String, String>()
        val reached = mutableSetOf<String>()
        var oldCursor = device.cursor
        device.pullAll()

        /** The request reached the server: its new mutations are applied there, in order. */
        fun applied(batch: List<PendingMutation>) {
            for (m in batch) {
                if (!reached.add(m.mutationId)) continue
                when (m.entity) {
                    "work_order" -> {
                        if (m.op == "CREATE") {
                            expected[m.entityId] = Expected(m.values["title"] as String, (m.values["status"] as String?) ?: "PLANNED", null)
                        } else {
                            val e = expected.getValue(m.entityId)
                            (m.values["status"] as String?)?.let { e.status = it } // the technician wins the status
                            if ("workNotes" in m.values) e.notes = m.values["workNotes"] as String?
                        }
                    }
                }
            }
        }

        fun rawPush(batch: List<PendingMutation>) =
            device.phone.post("/api/v1/mobile/sync/push", mapOf("protocolVersion" to 1, "mutations" to batch.map { it.toJson() }))

        repeat(steps) {
            val ids = expected.keys.filter { device.record("work_order", it) != null }
            when (random.nextInt(9)) {
                0, 1 -> {
                    if (ids.isNotEmpty()) {
                        val id = ids.random(random)
                        val values =
                            if (random.nextBoolean()) {
                                mapOf("status" to listOf("PLANNED", "IN_PROGRESS", "DONE").random(random))
                            } else {
                                mapOf("workNotes" to "Notiz ${random.nextInt(1000)}")
                            }
                        device.change("work_order", id, values)
                    }
                }

                2 -> {
                    applied(device.outbox.toList())
                    device.push()
                }

                3 -> {
                    // Timeout after commit: the server applied the batch, the answer never arrived.
                    val batch = device.outbox.toList()
                    if (batch.isNotEmpty()) {
                        assertEquals(200, rawPush(batch).status)
                        applied(batch)
                    }
                }

                4 -> {}

                // The connection dropped before the request reached the server.

                5 -> {
                    // The office edits meanwhile.
                    val id = expected.keys.random(random)
                    val e = expected.getValue(id)
                    val current = f.owner.get("/api/v1/work-orders/$id")
                    if (random.nextBoolean()) {
                        e.title = "Neu ${random.nextInt(1000)}"
                    } else {
                        e.status =
                            listOf("PLANNED", "IN_PROGRESS", "DONE").random(random)
                    }
                    val r =
                        f.owner.put(
                            "/api/v1/work-orders/$id",
                            mapOf(
                                "customerId" to current.tree.path("customerId").asString(),
                                "title" to e.title,
                                "assigneeIds" to listOf(f.tech.userId),
                                "status" to e.status,
                            ),
                            "If-Match" to current.header("ETag")!!,
                        )
                    assertEquals(200, r.status, "$r")
                }

                6 -> {
                    oldCursor = device.cursor.also { device.pullAll() }
                }

                7 -> {
                    // A replayed, older pull arrives late (out of order).
                    val saved = device.cursor
                    device.cursor = oldCursor
                    device.pullAll()
                    device.cursor = maxOf(saved.toLongOrNull() ?: 0, device.cursor.toLongOrNull() ?: 0).toString()
                }

                8 -> {
                    if (random.nextInt(3) == 0) {
                        // On site: a new customer and a work order for it.
                        val customerId = UUID.randomUUID().toString()
                        val name = "Vor Ort ${UUID.randomUUID().toString().take(8)}"
                        createdCustomers[customerId] = name
                        device.create("customer", customerId, mapOf("name" to name))
                        device.create(
                            "work_order",
                            UUID.randomUUID().toString(),
                            mapOf(
                                "customerId" to customerId,
                                "title" to "Notfall $name",
                            ),
                        )
                    } else {
                        // The same batch twice (a retry racing the first request).
                        val batch = device.outbox.toList()
                        if (batch.isNotEmpty()) {
                            rawPush(batch)
                            applied(batch)
                            device.push()
                        }
                    }
                }
            }
        }

        // The connection is good again: everything goes through.
        applied(device.outbox.toList())
        device.push()
        assertTrue(device.outbox.isEmpty())
        device.pullAll()

        for ((id, e) in expected) {
            val server = f.owner.get("/api/v1/work-orders/$id")
            assertEquals(200, server.status, "work order $id exists")
            val s = server.tree
            assertEquals(e.title, s.path("title").asString(), "title of $id")
            assertEquals(e.status, s.path("status").asString(), "status of $id")
            assertEquals(e.notes, s.path("workNotes").takeUnless { it.isMissingNode || it.isNull }?.asString(), "notes of $id")
            val local = device.record("work_order", id)?.data ?: error("work order $id missing on the phone")
            assertEquals(e.title, local["title"], "phone title of $id")
            assertEquals(e.status, local["status"], "phone status of $id")
            assertEquals(e.notes, local["workNotes"], "phone notes of $id")
        }
        for ((id, name) in createdCustomers) {
            assertEquals(
                name,
                f.owner
                    .get("/api/v1/customers/$id")
                    .tree
                    .path("name")
                    .asString(),
            )
            val count =
                f.owner
                    .get("/api/v1/customers?q=${name.replace(" ", "%20")}")
                    .tree
                    .path("items")
                    .size()
            assertEquals(1, count, "customer $name exists once")
        }
        val stored =
            TestDatabase.asOwner { c ->
                c.prepareStatement("SELECT count(*) FROM applied_mutation WHERE mutation_id = ANY(?)").use { ps ->
                    ps.setArray(1, c.createArrayOf("uuid", reached.toTypedArray()))
                    ps.executeQuery().run {
                        next()
                        getInt(1)
                    }
                }
            }
        assertEquals(reached.size, stored, "every mutation that reached the server is recorded exactly once")
    }

    /** A child record of a report as the server must hold it. */
    private class ExpectedChild(
        val entity: String,
        var values: Map<String, Any?>,
        var deleted: Boolean = false,
    )

    /**
     * MARA Stage 5: the same faults on a report's records, with deletes (ADR 0038). Every child
     * record created, changed or deleted on the phone ends up exactly so on the server and the
     * phone, however often requests were lost, repeated or answered late.
     */
    @Test
    fun `random faults on report records lose nothing and duplicate nothing`() {
        val seed = System.getenv("SYNC_FAULT_SEED")?.toLongOrNull() ?: System.nanoTime()
        val device = TestSyncDevice(firm.phone)
        device.pullAll()
        repeat(runs) { run ->
            val runSeed = seed + run
            try {
                reportRunOnce(Random(runSeed), device)
            } catch (e: Throwable) {
                throw AssertionError("report fault run failed; repeat with SYNC_FAULT_SEED=$runSeed SYNC_FAULT_RUNS=1", e)
            }
        }
    }

    private fun reportRunOnce(
        random: Random,
        device: TestSyncDevice,
    ) {
        val f = firm
        val order =
            f.owner
                .post(
                    "/api/v1/work-orders",
                    mapOf(
                        "customerId" to f.customer,
                        "title" to "Bericht ${UUID.randomUUID().toString().take(6)}",
                        "assigneeIds" to listOf(f.tech.userId),
                    ),
                    key(),
                ).tree
                .path("id")
                .asString()
        device.pullAll()
        val report =
            de.erika.reports.DerivedIds
                .report(UUID.fromString(order))
                .toString()
        var description: String? = null
        val expected = linkedMapOf<String, ExpectedChild>()
        val reached = mutableSetOf<String>()
        var oldCursor = device.cursor
        device.create("report", report, mapOf("workOrderId" to order))

        fun applied(batch: List<PendingMutation>) {
            for (m in batch) {
                if (!reached.add(m.mutationId)) continue
                if (m.entity == "report") {
                    if ("workDescription" in m.values) description = m.values["workDescription"] as String?
                    continue
                }
                when (m.op) {
                    "CREATE" -> expected[m.entityId] = ExpectedChild(m.entity, m.values - "reportId")
                    "UPDATE" -> expected.getValue(m.entityId).let { it.values = it.values + m.values }
                    "DELETE" -> expected.getValue(m.entityId).deleted = true
                }
            }
        }

        fun rawPush(batch: List<PendingMutation>) =
            device.phone.post("/api/v1/mobile/sync/push", mapOf("protocolVersion" to 1, "mutations" to batch.map { it.toJson() }))

        // Child records the phone holds and hasn't deleted yet.
        fun live() =
            (device.all("time_entry") + device.all("material_line")).filter { r ->
                r.data["reportId"] == report && device.outbox.none { it.entityId == r.id && it.op == "DELETE" }
            }

        repeat(steps) {
            when (random.nextInt(11)) {
                0 -> {
                    val day = random.nextInt(10)
                    device.create(
                        "time_entry",
                        UUID.randomUUID().toString(),
                        mapOf(
                            "reportId" to report,
                            "startedAt" to "2027-05-1${day}T07:00:00Z",
                        ),
                    )
                }

                1 -> {
                    device.create(
                        "material_line",
                        UUID.randomUUID().toString(),
                        mapOf(
                            "reportId" to report,
                            "description" to "Teil ${random.nextInt(1000)}",
                            "quantity" to "${1 + random.nextInt(9)}",
                            "unitCode" to "C62",
                        ),
                    )
                }

                2 -> {
                    live().randomOrNull(random)?.let { r ->
                        if (r.entity == "time_entry") {
                            device.change(r.entity, r.id, mapOf("note" to "Notiz ${random.nextInt(1000)}"))
                        } else {
                            device.change(r.entity, r.id, mapOf("quantity" to "${random.nextInt(1, 99)}.${random.nextInt(1, 10)}"))
                        }
                    }
                }

                3 -> {
                    live().randomOrNull(random)?.let { device.delete(it.entity, it.id) }
                }

                4 -> {
                    if (device.record("report", report) != null) {
                        device.change("report", report, mapOf("workDescription" to "Text ${random.nextInt(1000)}"))
                    }
                }

                5 -> {
                    applied(device.outbox.toList())
                    device.push()
                }

                6 -> {
                    // Timeout after commit.
                    val batch = device.outbox.toList()
                    if (batch.isNotEmpty()) {
                        assertEquals(200, rawPush(batch).status)
                        applied(batch)
                    }
                }

                7 -> {}

                // The connection dropped before the request reached the server.

                8 -> {
                    oldCursor = device.cursor.also { device.pullAll() }
                }

                9 -> {
                    // A replayed, older pull arrives late.
                    val saved = device.cursor
                    device.cursor = oldCursor
                    device.pullAll()
                    device.cursor = maxOf(saved.toLongOrNull() ?: 0, device.cursor.toLongOrNull() ?: 0).toString()
                }

                10 -> {
                    // The same batch twice.
                    val batch = device.outbox.toList()
                    if (batch.isNotEmpty()) {
                        rawPush(batch)
                        applied(batch)
                        device.push()
                    }
                }
            }
        }

        applied(device.outbox.toList())
        device.push()
        assertTrue(device.outbox.isEmpty())
        device.pullAll()
        val refused = device.results.filter { it.path("status").asString() == "REJECTED" }
        assertTrue(refused.isEmpty(), "nothing refused: $refused")
        device.results.clear()

        val office = f.owner.get("/api/v1/work-orders/$order/report").tree
        assertEquals(
            description,
            office
                .path("report")
                .path("workDescription")
                .takeIf { it.isString }
                ?.asString(),
            "work description",
        )
        val serverTimes = office.path("timeEntries").values().associateBy { it.path("id").asString() }
        val serverMaterials = office.path("materials").values().associateBy { it.path("id").asString() }
        for ((id, e) in expected) {
            val server = if (e.entity == "time_entry") serverTimes[id] else serverMaterials[id]
            val local = device.record(e.entity, id)
            if (e.deleted) {
                assertEquals(null, server, "deleted ${e.entity} $id is gone on the server")
                assertEquals(null, local, "deleted ${e.entity} $id is gone on the phone")
                continue
            }
            checkNotNull(server) { "${e.entity} $id exists on the server" }
            checkNotNull(local) { "${e.entity} $id exists on the phone" }
            for (field in listOf("note", "quantity", "description").filter { it in e.values }) {
                assertEquals(e.values[field], server.path(field).takeIf { it.isString }?.asString(), "server $field of $id")
                assertEquals(e.values[field], local.data[field], "phone $field of $id")
            }
        }
        assertEquals(expected.values.count { !it.deleted && it.entity == "time_entry" }, serverTimes.size, "no extra time entries")
        assertEquals(expected.values.count { !it.deleted && it.entity == "material_line" }, serverMaterials.size, "no extra materials")
    }

    @Test
    fun `parallel office writes never slip past a phone's cursor`() {
        val f = firm
        val device = TestSyncDevice(f.phone)
        device.pullAll()
        val names = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(4)
        val writers =
            (1..4).map { w ->
                pool.submit {
                    // One office session, several requests at once (several browser tabs).
                    val c = f.owner
                    repeat(10) { i ->
                        val name = "Parallel $w-$i ${UUID.randomUUID().toString().take(6)}"
                        val r =
                            c.post(
                                "/api/v1/customers",
                                mapOf("name" to name, "countryCode" to "DE", "vatTreatment" to "DOMESTIC"),
                                key(),
                            )
                        check(r.status == 201) { "$r" }
                        names += name
                    }
                }
            }
        // Pull all the time while the writers commit in parallel (and out of order).
        while (writers.any { !it.isDone }) device.pullAll()
        writers.forEach { it.get() }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
        device.pullAll()
        val onPhone = device.all("customer").map { it.data["name"] }.toSet()
        val missing = names.filterNot { it in onPhone }
        assertTrue(missing.isEmpty(), "every committed change reaches the phone; missing: $missing")
    }
}
