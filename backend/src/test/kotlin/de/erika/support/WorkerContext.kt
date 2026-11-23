package de.erika.support

import de.erika.ErikaApplication
import de.erika.platform.jobs.JobWorker
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.time.Duration

/**
 * A worker process next to an API integration test: the `worker` role started in the same
 * JVM against the same containers, with polling off. API tests call [drain] to run the
 * background jobs their requests queued, so a test can go browser-request → job → result
 * through both runtime roles, as in production.
 */
object WorkerContext {
    private val context: ConfigurableApplicationContext by lazy {
        val settings =
            TestInfrastructure.properties() +
                mapOf(
                    "erika.jobs.polling-enabled" to "false",
                    "spring.flyway.enabled" to "false",
                    "management.server.port" to "0",
                    "erika.worker.metrics-port" to "0",
                )
        // As command-line arguments: they outrank application.yml (default properties wouldn't).
        SpringApplicationBuilder(ErikaApplication::class.java)
            .profiles("worker", "test")
            .run(*settings.map { (k, v) -> "--$k=$v" }.toTypedArray())
    }

    fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

    /** Runs due jobs until none are left (or [timeout] passes); returns how many ran. */
    fun drain(timeout: Duration = Duration.ofMinutes(2)): Int {
        val worker = bean(JobWorker::class.java)
        val deadline = System.nanoTime() + timeout.toNanos()
        var total = 0
        while (System.nanoTime() < deadline) {
            val n = worker.runOnce()
            if (n == 0) return total
            total += n
        }
        error("Job queue not drained within $timeout")
    }
}
