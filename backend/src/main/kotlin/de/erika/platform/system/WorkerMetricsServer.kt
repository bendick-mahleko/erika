package de.erika.platform.system

import com.sun.net.httpserver.HttpServer
import de.erika.platform.runtime.RuntimeInstance
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Prometheus endpoint of the worker (ADR 0029). The worker runs without a web server, so
 * Spring's actuator can't serve its metrics; this small JDK HTTP server does, on
 * `erika.worker.metrics-port` (default 8082; 0 = any free port, -1 = off):
 * - `GET /metrics`: the worker's meters, e.g. `erika_audit_chain_broken`,
 *   `erika_archive_integrity_failures_total`, `erika_documents_processing_failed_total`,
 *   JVM and job metrics.
 *
 * Not routed by the reverse proxy; Prometheus scrapes it over the private network.
 */
@Component
@Profile(RuntimeInstance.PROFILE_WORKER)
class WorkerMetricsServer(
    private val registry: PrometheusMeterRegistry,
    @param:Value("\${erika.worker.metrics-port:8082}") private val port: Int,
    @param:Value("\${erika.worker.metrics-address:0.0.0.0}") private val address: String,
) : InitializingBean,
    DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private var server: HttpServer? = null

    /** The port actually bound (useful when configured as 0), or -1 when off. */
    val boundPort: Int get() = server?.address?.port ?: -1

    override fun afterPropertiesSet() {
        if (port < 0) return
        val http = HttpServer.create(InetSocketAddress(address, port), 0)
        http.executor = Executors.newVirtualThreadPerTaskExecutor()
        http.createContext("/metrics") { exchange ->
            exchange.use {
                if (it.requestMethod != "GET") {
                    it.sendResponseHeaders(405, -1)
                    return@use
                }
                val body = registry.scrape().toByteArray()
                it.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        http.createContext("/") { exchange -> exchange.use { it.sendResponseHeaders(404, -1) } }
        http.start()
        server = http
        log.info("Worker metrics on port {} (/metrics)", boundPort)
    }

    override fun destroy() {
        server?.stop(0)
    }
}
