package de.erika.platform.system

import de.erika.support.WorkerIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The worker's Prometheus endpoint (ADR 0029): the alerts "audit chain broken" and
 * "archive integrity failure" need the worker's own meters.
 */
class WorkerMetricsIT : WorkerIntegrationTest() {
    @Autowired lateinit var server: WorkerMetricsServer

    private val http = HttpClient.newHttpClient()

    private fun get(path: String) =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:${server.boundPort}$path")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `the worker serves its meters in Prometheus format`() {
        val response = get("/metrics")
        assertEquals(200, response.statusCode())
        assertTrue(
            response
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .startsWith("text/plain"),
        )
        val body = response.body()
        listOf("erika_audit_chain_broken", "jvm_memory_used_bytes").forEach {
            assertTrue(body.lines().any { line -> line.startsWith(it) }, "missing metric $it")
        }
    }

    @Test
    fun `nothing else is served`() {
        assertEquals(404, get("/actuator/env").statusCode())
    }
}
