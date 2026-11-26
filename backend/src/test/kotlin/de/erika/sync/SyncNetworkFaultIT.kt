package de.erika.sync

import de.erika.support.ApiIntegrationTest
import de.erika.support.MobileClient
import de.erika.support.Phones
import de.erika.support.TestSyncDevice
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sync protocol through a real faulty network (toxiproxy, ADR 0038): the phone talks to the
 * API through a proxy that drops answers after the server committed, resets connections and
 * adds latency. Whatever the network does, the phone retries until it gets through, and in the
 * end nothing is lost or applied twice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncNetworkFaultIT : ApiIntegrationTest() {
    private val phones by lazy { Phones(this) }
    private val http = HttpClient.newHttpClient()

    /** Started once, on first use; [admin] must not touch this property while it starts. */
    private val toxiproxy: GenericContainer<*> by lazy {
        Testcontainers.exposeHostPorts(port)
        val container =
            GenericContainer("ghcr.io/shopify/toxiproxy:2.12.0")
                .withExposedPorts(8474, 8666)
                .waitingFor(Wait.forHttp("/version").forPort(8474))
        container.start()
        toxiproxyStarted = true
        admin(container, "POST", "/proxies", """{"name":"api","listen":"0.0.0.0:8666","upstream":"host.testcontainers.internal:$port"}""")
        container
    }

    @AfterAll
    fun stop() {
        if (toxiproxyStarted) toxiproxy.stop()
    }

    private var toxiproxyStarted = false

    private fun admin(
        container: GenericContainer<*>,
        method: String,
        path: String,
        body: String? = null,
    ): Int {
        val url = "http://${container.host}:${container.getMappedPort(8474)}$path"
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun toxic(json: String) = check(admin(toxiproxy, "POST", "/proxies/api/toxics", json) in 200..299)

    private fun healthy() {
        listOf("drop", "reset", "slow").forEach { admin(toxiproxy, "DELETE", "/proxies/api/toxics/$it") }
    }

    @Test
    fun `through dropped answers, resets and latency the phone's changes arrive exactly once`() {
        val owner = client()
        accounts.enrolled("netz", owner)
        val (direct, tech) = phones.signIn(phones.technician(owner, "netz-tech"))
        val proxied =
            MobileClient(
                "http://${toxiproxy.host}:${toxiproxy.getMappedPort(8666)}",
                JsonMapper.builder().build(),
                timeout = Duration.ofSeconds(3),
            ).apply {
                accessToken = direct.accessToken
                refreshToken = direct.refreshToken
                deviceId = direct.deviceId
            }
        val customer =
            owner
                .post(
                    "/api/v1/customers",
                    mapOf("name" to "Netzkunde", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"),
                    "Idempotency-Key" to UUID.randomUUID().toString(),
                ).tree
                .path("id")
                .asString()
        val orders =
            (1..3).map {
                owner
                    .post(
                        "/api/v1/work-orders",
                        mapOf("customerId" to customer, "title" to "Netzauftrag $it", "assigneeIds" to listOf(tech.userId)),
                        "Idempotency-Key" to UUID.randomUUID().toString(),
                    ).tree
                    .path("id")
                    .asString()
            }
        val device = TestSyncDevice(proxied)
        device.pullAll()

        val random = Random(System.getenv("SYNC_FAULT_SEED")?.toLongOrNull() ?: System.nanoTime())
        val lastNotes = mutableMapOf<String, String>()
        var failures = 0
        repeat(30) { i ->
            val order = orders.random(random)
            val notes = "Netznotiz $i"
            device.change("work_order", order, mapOf("workNotes" to notes))
            lastNotes[order] = notes
            when (random.nextInt(4)) {
                // The server's answer is cut off after a few bytes: the push committed, the phone doesn't know.
                0 -> {
                    toxic("""{"name":"drop","type":"limit_data","stream":"downstream","attributes":{"bytes":40}}""")
                }

                // The connection is reset while the request is on its way.
                1 -> {
                    toxic("""{"name":"reset","type":"reset_peer","stream":"upstream","attributes":{"timeout":20}}""")
                }

                // A slow mobile network.
                2 -> {
                    toxic("""{"name":"slow","type":"latency","stream":"downstream","attributes":{"latency":800,"jitter":400}}""")
                }

                else -> {}
            }
            try {
                device.push()
                device.pullAll()
            } catch (_: Exception) {
                failures++ // The phone keeps the outbox and tries again later.
            }
            healthy()
        }
        // Good network again: the outbox drains.
        repeat(5) { if (device.outbox.isNotEmpty()) device.push() }
        device.pullAll()
        assertTrue(device.outbox.isEmpty(), "outbox drained")
        assertTrue(failures > 0, "the network did fail sometimes")

        for ((order, notes) in lastNotes) {
            assertEquals(
                notes,
                owner
                    .get("/api/v1/work-orders/$order")
                    .tree
                    .path("workNotes")
                    .asString(),
                "server has the last notes of $order",
            )
            assertEquals(notes, device.record("work_order", order)!!.data["workNotes"], "phone has the last notes of $order")
        }
        val sent = device.results.map { it.path("mutationId").asString() }.toSet()
        val applied =
            de.erika.support.TestDatabase.asOwner { c ->
                c.prepareStatement("SELECT count(*) FROM applied_mutation WHERE device_id = ?::uuid").use { ps ->
                    ps.setString(1, direct.deviceId)
                    ps.executeQuery().run {
                        next()
                        getInt(1)
                    }
                }
            }
        assertEquals(30, applied, "each of the 30 changes was applied exactly once (${sent.size} answers seen)")
    }
}
