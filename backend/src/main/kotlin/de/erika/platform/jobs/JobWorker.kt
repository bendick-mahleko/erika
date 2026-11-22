package de.erika.platform.jobs

import de.erika.platform.runtime.RuntimeInstance
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * Claims due jobs with `FOR UPDATE SKIP LOCKED` and runs them (ADR 0010).
 *
 * - Each job runs in its own transaction together with its SUCCEEDED mark.
 * - A failed job is retried with exponential backoff until `max_attempts`, then FAILED.
 * - A job whose lease expired (worker crashed) is reclaimed by the next poll; the
 *   attempt counter still increases, so a job that crashes the worker can't loop forever.
 * - Completion is only recorded while this worker still holds the lease.
 */
@Component
@Profile(RuntimeInstance.PROFILE_WORKER)
class JobWorker(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
    private val tx: TransactionTemplate,
    private val properties: JobProperties,
    private val instance: RuntimeInstance,
    private val meters: MeterRegistry,
    handlers: List<JobHandler<*>>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlers: Map<String, JobHandler<*>> = handlers.associateBy { it.type }

    /** Claims and runs one batch of due jobs. Returns the number of jobs claimed. */
    fun runOnce(): Int {
        val jobs = claim()
        jobs.forEach(::execute)
        return jobs.size
    }

    private fun claim(): List<ClaimedJob> =
        tx.execute {
            jdbc
                .sql(
                    """
                    UPDATE job
                       SET state = 'RUNNING', attempts = attempts + 1,
                           locked_by = :worker, locked_until = now() + make_interval(secs => :leaseSeconds)
                     WHERE id IN (SELECT id FROM job
                                   WHERE (state = 'QUEUED' AND run_at <= now())
                                      OR (state = 'RUNNING' AND locked_until < now())
                                   ORDER BY run_at
                                   LIMIT :batchSize
                                   FOR UPDATE SKIP LOCKED)
                    RETURNING id, type, payload::text AS payload, workspace_id, attempts, max_attempts
                    """.trimIndent(),
                ).param("worker", instance.id)
                .param("leaseSeconds", properties.lease.toSeconds())
                .param("batchSize", properties.batchSize)
                .query { rs, _ ->
                    ClaimedJob(
                        id = rs.getObject("id", UUID::class.java),
                        type = rs.getString("type"),
                        payload = rs.getString("payload"),
                        workspaceId = rs.getObject("workspace_id", UUID::class.java),
                        attempts = rs.getInt("attempts"),
                        maxAttempts = rs.getInt("max_attempts"),
                    )
                }.list()
        } ?: emptyList()

    private fun execute(job: ClaimedJob) {
        MDC.putCloseable("jobId", job.id.toString()).use {
            MDC.putCloseable("jobType", job.type).use {
                val handler = handlers[job.type]
                when {
                    handler == null -> fail(job, "No handler registered for job type '${job.type}'", final = true)
                    job.attempts > job.maxAttempts -> fail(job, "Lease expired after the last attempt", final = true)
                    else -> run(job, handler)
                }
            }
        }
    }

    private fun run(
        job: ClaimedJob,
        handler: JobHandler<*>,
    ) {
        val started = System.nanoTime()
        try {
            tx.executeWithoutResult {
                job.workspaceId?.let(::scopeTransactionToWorkspace)
                invoke(handler, job)
                val updated =
                    jdbc
                        .sql(
                            """
                            UPDATE job SET state = 'SUCCEEDED', finished_at = now(), last_error = NULL,
                                           locked_by = NULL, locked_until = NULL
                             WHERE id = :id AND locked_by = :worker
                            """.trimIndent(),
                        ).param("id", job.id)
                        .param("worker", instance.id)
                        .update()
                // Lease lost to another worker: roll back so the handler's writes don't commit twice.
                if (updated != 1) throw LeaseLostException(job.id)
            }
            record(job, "succeeded", started)
            log.info("Job {} ({}) succeeded on attempt {}", job.id, job.type, job.attempts)
        } catch (e: LeaseLostException) {
            record(job, "lease_lost", started)
            log.warn("Job {} ({}) lost its lease; result discarded", job.id, job.type)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            record(job, "failed", started)
            val final = job.attempts >= job.maxAttempts
            log.warn("Job {} ({}) failed on attempt {}/{}", job.id, job.type, job.attempts, job.maxAttempts, e)
            fail(job, "${e.javaClass.name}: ${e.message}", final)
        }
    }

    private fun scopeTransactionToWorkspace(workspaceId: UUID) {
        // Equivalent to SET LOCAL app.workspace_id (Software Design §7), but parameterised.
        jdbc
            .sql("SELECT set_config('app.workspace_id', :ws, true)")
            .param("ws", workspaceId.toString())
            .query(String::class.java)
            .single()
    }

    @Suppress("UNCHECKED_CAST")
    private fun invoke(
        handler: JobHandler<*>,
        job: ClaimedJob,
    ) {
        val typed = handler as JobHandler<Any>
        val payload = jsonMapper.readValue(job.payload, typed.payloadType)
        typed.handle(payload, JobContext(job.id, job.workspaceId, job.attempts))
    }

    private fun fail(
        job: ClaimedJob,
        error: String,
        final: Boolean,
    ) {
        tx.executeWithoutResult {
            jdbc
                .sql(
                    """
                    UPDATE job
                       SET state = CASE WHEN :final THEN 'FAILED' ELSE 'QUEUED' END,
                           finished_at = CASE WHEN :final THEN now() END,
                           run_at = now() + make_interval(secs => :delaySeconds),
                           last_error = :error, locked_by = NULL, locked_until = NULL
                     WHERE id = :id AND locked_by = :worker
                    """.trimIndent(),
                ).param("final", final)
                .param("delaySeconds", if (final) 0 else retryDelay(job.attempts).toSeconds())
                .param("error", error.take(MAX_ERROR_LENGTH))
                .param("id", job.id)
                .param("worker", instance.id)
                .update()
        }
        if (final) log.error("Job {} ({}) marked FAILED: {}", job.id, job.type, error.take(MAX_ERROR_LENGTH))
    }

    private fun record(
        job: ClaimedJob,
        outcome: String,
        startedNanos: Long,
    ) {
        meters
            .timer("erika.jobs.execution", "type", job.type, "outcome", outcome)
            .record(Duration.ofNanos(System.nanoTime() - startedNanos))
    }

    private data class ClaimedJob(
        val id: UUID,
        val type: String,
        val payload: String,
        val workspaceId: UUID?,
        val attempts: Int,
        val maxAttempts: Int,
    )

    private class LeaseLostException(
        id: UUID,
    ) : RuntimeException("Lease lost for job $id")

    companion object {
        private const val MAX_ERROR_LENGTH = 2000
        private val BASE_DELAY: Duration = Duration.ofSeconds(10)
        private val MAX_DELAY: Duration = Duration.ofHours(1)

        /** Exponential backoff: 10 s, 20 s, 40 s, … capped at 1 h. */
        fun retryDelay(attempt: Int): Duration {
            val exponent = (attempt - 1).coerceIn(0, 20)
            val delay = BASE_DELAY.multipliedBy(1L shl exponent)
            return if (delay > MAX_DELAY) MAX_DELAY else delay
        }
    }
}
