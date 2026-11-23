package de.erika.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Base for integration tests that need the worker role with a migrated database.
 * Polling is off so tests drive the worker explicitly via `JobWorker.runOnce()`.
 */
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "erika.jobs.polling-enabled=false",
        "erika.worker.metrics-port=0",
    ],
)
@ActiveProfiles("worker", "test")
abstract class WorkerIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun containers(registry: DynamicPropertyRegistry) = TestInfrastructure.register(registry)
    }
}
