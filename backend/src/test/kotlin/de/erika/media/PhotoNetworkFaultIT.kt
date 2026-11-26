package de.erika.media

import de.erika.reports.DerivedIds
import de.erika.support.ApiIntegrationTest
import de.erika.support.MobileClient
import de.erika.support.Phones
import de.erika.support.TestDatabase
import de.erika.support.TestPhotos
import de.erika.support.TestSyncDevice
import de.erika.support.TestTus
import de.erika.support.WorkerContext
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
 * Stage 6 exit criterion (M-MO-05): 20 photos of one job reach the server completely through a
 * throttled, interrupted connection (toxiproxy), with no duplicates. The phone uploads with tus
 * and resumes from the server's offset after every failure.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoNetworkFaultIT : ApiIntegrationTest() {
    private val phones by lazy { Phones(this) }
    private val http = HttpClient.newHttpClient()
    private var started = false

    private val toxiproxy: GenericContainer<*> by lazy {
        Testcontainers.exposeHostPorts(port)
        val container =
            GenericContainer("ghcr.io/shopify/toxiproxy:2.12.0")
                .withExposedPorts(8474, 8666)
                .waitingFor(Wait.forHttp("/version").forPort(8474))
        container.start()
        started = true
        admin(container, "POST", "/proxies", """{"name":"api","listen":"0.0.0.0:8666","upstream":"host.testcontainers.internal:$port"}""")
        container
    }

    @AfterAll
    fun stop() {
        if (started) toxiproxy.stop()
    }

    private fun admin(
        container: GenericContainer<*>,
        method: String,
        path: String,
        body: String? = null,
    ): Int {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://${container.host}:${container.getMappedPort(8474)}$path"))
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun toxic(json: String) = check(admin(toxiproxy, "POST", "/proxies/api/toxics", json) in 200..299)

    private fun healthy() = listOf("cut", "reset", "slow", "throttle").forEach { admin(toxiproxy, "DELETE", "/proxies/api/toxics/$it") }

    @Test
    fun `20 photos arrive completely through a throttled, interrupted connection, without duplicates`() {
        val owner = client()
        accounts.enrolled("foto-netz", owner)
        val (direct, tech) = phones.signIn(phones.technician(owner, "foto-netz-tech"))
        val key = { "Idempotency-Key" to UUID.randomUUID().toString() }
        val customer =
            owner
                .post("/api/v1/customers", mapOf("name" to "Netzkunde", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"), key())
                .tree
                .path("id")
                .asString()
        val order =
            owner
                .post(
                    "/api/v1/work-orders",
                    mapOf("customerId" to customer, "title" to "Fotos", "assigneeIds" to listOf(tech.userId)),
                    key(),
                ).tree
                .path("id")
                .asString()

        // The records sync first (on a good connection); the files follow over the bad one.
        val device = TestSyncDevice(direct)
        device.pullAll()
        val report = DerivedIds.report(UUID.fromString(order)).toString()
        device.create("report", report, mapOf("workOrderId" to order))
        val files = (1..20).associate { UUID.randomUUID().toString() to TestPhotos.jpeg(640, 480, seed = it) }
        files.forEach { (id, bytes) -> device.create("photo", id, TestPhotos.record(report, bytes)) }
        device.push()

        val proxied =
            MobileClient(
                "http://${toxiproxy.host}:${toxiproxy.getMappedPort(8666)}",
                JsonMapper.builder().build(),
                timeout = Duration.ofSeconds(4),
            ).apply { accessToken = direct.accessToken }
        val tus = TestTus(proxied)
        val random = Random(System.getenv("SYNC_FAULT_SEED")?.toLongOrNull() ?: System.nanoTime())
        val done = mutableSetOf<String>()
        var failures = 0
        var round = 0
        // A mobile network: always slow and narrow; for each photo, a fresh chance that the
        // connection is cut off after a few kilobytes or reset.
        toxic("""{"name":"slow","type":"latency","stream":"downstream","attributes":{"latency":150,"jitter":100}}""")
        toxic("""{"name":"throttle","type":"bandwidth","stream":"upstream","attributes":{"rate":256}}""")
        while (done.size < files.size && round < 400) {
            round++
            val (id, bytes) =
                files.entries
                    .filter { it.key !in done }
                    .random(random)
                    .toPair()
            when (random.nextInt(3)) {
                0 -> {
                    val bytesUntilCut = 20_000 + random.nextInt(60_000)
                    toxic("""{"name":"cut","type":"limit_data","stream":"upstream","attributes":{"bytes":$bytesUntilCut}}""")
                }

                1 -> {
                    val afterMillis = 200 + random.nextInt(800)
                    toxic("""{"name":"reset","type":"reset_peer","stream":"upstream","attributes":{"timeout":$afterMillis}}""")
                }

                else -> {}
            }
            try {
                if (tus.upload(id, bytes, 32 * 1024) == 204 && tus.offset(id) == bytes.size.toLong()) done += id
            } catch (_: Exception) {
                failures++ // the connection is gone; the phone tries again later from the server's offset
            }
            listOf("cut", "reset").forEach { admin(toxiproxy, "DELETE", "/proxies/api/toxics/$it") }
        }
        healthy()
        assertEquals(files.size, done.size, "all photos uploaded within $round rounds")
        assertTrue(failures > 0, "the network did fail sometimes")

        WorkerContext.drain()
        for ((id, bytes) in files) {
            assertEquals(
                listOf("STORED:${bytes.size}:${TestPhotos.sha256(bytes)}"),
                TestDatabase.strings("SELECT state || ':' || size_bytes || ':' || sha256 FROM photo WHERE id = ?::uuid", id),
            )
        }
        assertEquals(20, TestDatabase.count("SELECT count(*) FROM photo WHERE report_id = ?::uuid", report), "no duplicates")
        val stored = owner.get("/api/v1/work-orders/$order/report").tree.path("photos")
        assertEquals(20, stored.size())
        assertTrue(stored.values().all { it.path("state").asString() == "STORED" })
    }
}
