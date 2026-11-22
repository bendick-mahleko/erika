package de.erika.platform.jobs

import de.erika.platform.runtime.RuntimeInstance
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Job queue gauges for alerting (Software Design §8: "intake queue older than 5 minutes,
 * failed jobs"); alert rules in infra/monitoring/alerts.yml:
 * - `erika_jobs_oldest_due_seconds{type}`: age of the oldest due job that hasn't run yet,
 * - `erika_jobs_failed_recent{type}`: jobs that ended FAILED in the last 24 hours.
 *
 * Counts only; the job table is a system table, so this needs no workspace scope. Published
 * by the API, whose actuator Prometheus scrapes (the worker runs without a web server).
 */
@Component
@Profile(RuntimeInstance.PROFILE_API)
@EnableScheduling
class JobQueueMetrics(
    private val jdbc: JdbcClient,
    meters: MeterRegistry,
) {
    private val oldestDue = MultiGauge.builder("erika.jobs.oldest.due").baseUnit("seconds").register(meters)
    private val failedRecent = MultiGauge.builder("erika.jobs.failed.recent").register(meters)

    @Scheduled(fixedDelayString = "\${erika.jobs.metrics-interval:30s}", initialDelayString = "\${erika.jobs.metrics-interval:30s}")
    fun refresh() {
        val due =
            jdbc
                .sql(
                    """
                    SELECT type, extract(epoch FROM now() - min(run_at)) AS age
                      FROM job WHERE state = 'QUEUED' AND run_at <= now() GROUP BY type
                    """.trimIndent(),
                ).query { rs, _ -> rs.getString("type") to rs.getDouble("age") }
                .list()
        oldestDue.register(due.map { (type, age) -> MultiGauge.Row.of(Tags.of("type", type), age) }, true)

        val failed =
            jdbc
                .sql(
                    "SELECT type, count(*) AS n FROM job WHERE state = 'FAILED' AND finished_at > now() - interval '24 hours' GROUP BY type",
                ).query { rs, _ -> rs.getString("type") to rs.getLong("n") }
                .list()
        failedRecent.register(failed.map { (type, n) -> MultiGauge.Row.of(Tags.of("type", type), n) }, true)
    }
}
