package de.erika.platform.jobs

import java.util.UUID

/**
 * Handles one job type. Implementations are Spring beans and must be **idempotent**:
 * a job can run more than once (retry after a failure, lease expiry after a crash).
 *
 * The handler runs inside a transaction together with the job's completion mark,
 * so database writes and "job done" commit or roll back together. When the job
 * belongs to a workspace, `app.workspace_id` is set for that transaction, so
 * row-level security applies as it does for API requests.
 *
 * Exception messages are stored in `job.last_error`; don't put invoice content in them.
 */
interface JobHandler<P : Any> {
    /** Stable identifier stored in `job.type`, e.g. `intake.parse-and-validate`. */
    val type: String

    /** Class the JSON payload is deserialised into. */
    val payloadType: Class<P>

    fun handle(
        payload: P,
        context: JobContext,
    )
}

data class JobContext(
    val jobId: UUID,
    val workspaceId: UUID?,
    val attempt: Int,
)
