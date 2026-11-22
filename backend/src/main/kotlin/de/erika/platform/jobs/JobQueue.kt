package de.erika.platform.jobs

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.sql.Types
import java.time.Duration
import java.util.UUID

/**
 * Writes jobs into the PostgreSQL `job` table (ADR 0010).
 *
 * `enqueue` joins the caller's transaction when one is active, so a job is
 * queued if and only if the caller's own writes commit (e.g. "create document
 * row and queue ParseAndValidate in the same transaction", Design Flow A step 4).
 */
@Component
class JobQueue(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
    private val properties: JobProperties,
    handlers: List<JobHandler<*>>,
) {
    private val knownTypes: Set<String> = handlers.map { it.type }.toSet()

    fun enqueue(
        type: String,
        payload: Any,
        workspaceId: UUID? = null,
        delay: Duration = Duration.ZERO,
        maxAttempts: Int = properties.defaultMaxAttempts,
    ): UUID {
        require(type in knownTypes) { "No JobHandler registered for job type '$type'" }
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO job (id, type, payload, workspace_id, run_at, max_attempts)
                VALUES (:id, :type, CAST(:payload AS jsonb), :workspaceId,
                        now() + make_interval(secs => :delaySeconds), :maxAttempts)
                """.trimIndent(),
            ).param("id", id)
            .param("type", type)
            .param("payload", jsonMapper.writeValueAsString(payload))
            .param("workspaceId", workspaceId, Types.OTHER)
            .param("delaySeconds", delay.toMillis() / 1000.0)
            .param("maxAttempts", maxAttempts)
            .update()
        return id
    }
}
