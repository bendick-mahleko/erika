package de.erika

import de.erika.platform.system.StartupProbeJobHandler
import de.erika.support.TestInfrastructure
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Stage 1 exit criteria, end to end: the API and the worker run as two separate
 * processes (here: two application contexts) against real PostgreSQL and S3.
 * The API queues a job, the worker runs it, and /api/v1/health reports every
 * component UP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalkingSkeletonIT {
    private lateinit var api: ConfigurableApplicationContext
    private lateinit var worker: ConfigurableApplicationContext
    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun start() {
        api = start("api", "server.port=0", "management.server.port=0")
        worker = start("worker", "erika.jobs.poll-interval=200ms")
    }

    @AfterAll
    fun stop() {
        if (::worker.isInitialized) worker.close()
        if (::api.isInitialized) api.close()
    }

    @Test
    fun `startup probe queued by the API is processed by the worker`() {
        val jdbc = api.getBean(JdbcClient::class.java)
        awaitTrue("startup probe job succeeded") {
            jdbc
                .sql("SELECT count(*) FROM job WHERE type = :type AND state = 'SUCCEEDED'")
                .param("type", StartupProbeJobHandler.TYPE)
                .query(Long::class.java)
                .single() > 0
        }
    }

    @Test
    fun `health reports database, object storage and worker UP`() {
        val json = api.getBean(JsonMapper::class.java)
        var body = ""
        awaitTrue("health UP") {
            val response = get("/api/v1/health")
            body = response.body()
            response.statusCode() == 200 && json.readTree(body).path("status").asString() == "UP"
        }
        val tree = json.readTree(body)
        listOf("database", "objectStorage", "worker").forEach {
            assertEquals(
                "UP",
                tree
                    .path("components")
                    .path(it)
                    .path("status")
                    .asString(),
                "component $it",
            )
            // Optional fields are omitted, not null (OpenAPI: `detail` is not nullable).
            assertTrue(!tree.path("components").path(it).has("detail"), "component $it must not carry detail when UP")
        }
        assertEquals("dev", tree.path("version").asString())
    }

    @Test
    fun `unknown paths return problem details with a stable code`() {
        val response = get("/api/v1/does-not-exist")

        assertEquals(404, response.statusCode())
        assertTrue(
            response
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .startsWith("application/problem+json"),
        )
        val tree = api.getBean(JsonMapper::class.java).readTree(response.body())
        assertEquals("NOT_FOUND", tree.path("code").asString())
    }

    @Test
    fun `responses carry a request id`() {
        val response = get("/api/v1/health", "X-Request-Id" to "walking-skeleton-1")
        assertEquals("walking-skeleton-1", response.headers().firstValue("X-Request-Id").orElse(null))
    }

    private fun start(
        role: String,
        vararg extra: String,
    ): ConfigurableApplicationContext {
        // Command-line arguments: highest precedence, like real deployments' environment.
        // (SpringApplicationBuilder.properties() would only set low-precedence defaults.)
        val args = (TestInfrastructure.properties().map { (k, v) -> "$k=$v" } + extra).map { "--$it" }
        return SpringApplicationBuilder(ErikaApplication::class.java)
            .profiles(role, "test")
            .run(*args.toTypedArray())
    }

    private fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> {
        val port = api.environment.getRequiredProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .GET()
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun awaitTrue(
        what: String,
        timeout: Duration = Duration.ofSeconds(30),
        condition: () -> Boolean,
    ) {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(250)
        }
        fail("Timed out waiting for: $what")
    }
}
