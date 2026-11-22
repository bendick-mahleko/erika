package de.erika.platform

import de.erika.support.ApiIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Regression test: each signed-in request holds one pooled connection for its tenant
 * transaction. If the response were committed inside that transaction, Spring Session would
 * save the session in a second transaction needing a second connection, and with more
 * concurrent requests than connections every request would wait for another until the pool
 * timed out. With a pool of 3, 24 concurrent requests must all succeed.
 */
@TestPropertySource(
    properties = [
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=5000",
    ],
)
class TenantTransactionConcurrencyIT : ApiIntegrationTest() {
    @Test
    fun `many concurrent signed-in requests don't exhaust a small connection pool`() {
        val clients = (1..4).map { i -> client().also { accounts.enrolled("pool-$i", it) } }
        val pool = Executors.newFixedThreadPool(24)
        try {
            val calls =
                (1..24).map { n ->
                    Callable {
                        val c = clients[n % clients.size]
                        listOf("/api/v1/documents", "/api/v1/members", "/api/v1/inbox/summary").map { c.get(it).status }
                    }
                }
            val statuses = pool.invokeAll(calls, 60, TimeUnit.SECONDS).flatMap { it.get() }
            assertEquals(List(statuses.size) { 200 }, statuses)
        } finally {
            pool.shutdownNow()
        }
    }
}
