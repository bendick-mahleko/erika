package de.erika.archive

import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.tenancy.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class ArchiveIntegrityCheckFailedException(
    failures: Int,
    checked: Int,
) : RuntimeException("$failures of $checked archived originals failed the integrity check; see the error log")

/**
 * Re-hashes stored originals (Software Design §7): weekly a random sample (1 %), once a
 * year all of them. Failures are logged at ERROR (alert), counted in
 * `erika.archive.integrity.failures`, audited, and fail the job.
 *
 * Idempotent: re-running only re-reads and re-hashes.
 */
@Component
class ArchiveIntegrityCheckJobHandler(
    private val archive: Archive,
    private val jdbc: JdbcClient,
    private val tenant: TenantContext,
) : JobHandler<ArchiveIntegrityCheckJobHandler.Payload> {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Payload(
        /** Share of all originals to check, 0 < fraction <= 1. */
        val fraction: Double = 0.01,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        require(payload.fraction > 0 && payload.fraction <= 1) { "fraction must be in (0, 1]" }
        val sample =
            jdbc
                .sql("SELECT workspace_id, sha256 FROM stored_object_sample(:fraction)")
                .param("fraction", payload.fraction)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }
                .list()
        var failures = 0
        for ((workspaceId, sha256) in sample) {
            tenant.enterWorkspace(workspaceId)
            val stored = archive.find(workspaceId, sha256) ?: continue
            val problem = archive.verify(stored)
            if (problem != null) {
                failures++
                archive.recordIntegrityFailure(stored, problem)
            }
        }
        log.info("Archive integrity check: {} originals checked, {} failed (fraction {})", sample.size, failures, payload.fraction)
        if (failures > 0) throw ArchiveIntegrityCheckFailedException(failures, sample.size)
    }

    companion object {
        const val TYPE = "archive.verify-integrity"
    }
}

/** Queues the weekly sample and the yearly full check in the worker (one queued check at a time). */
@Component
@Profile(RuntimeInstance.PROFILE_WORKER)
class ArchiveIntegrityCheckScheduler(
    private val jobs: JobQueue,
    private val jdbc: JdbcClient,
    private val props: ArchiveProperties,
) {
    @Scheduled(cron = "\${erika.archive.weekly-cron:0 0 4 * * SUN}", zone = "Europe/Berlin")
    @Transactional
    fun weekly() = enqueue(props.weeklySampleFraction)

    @Scheduled(cron = "\${erika.archive.yearly-cron:0 30 4 5 1 *}", zone = "Europe/Berlin")
    @Transactional
    fun yearly() = enqueue(1.0)

    private fun enqueue(fraction: Double) {
        val pending =
            jdbc
                .sql("SELECT count(*) FROM job WHERE type = :type AND state IN ('QUEUED', 'RUNNING')")
                .param("type", ArchiveIntegrityCheckJobHandler.TYPE)
                .query(Long::class.java)
                .single()
        if (pending == 0L) {
            jobs.enqueue(ArchiveIntegrityCheckJobHandler.TYPE, ArchiveIntegrityCheckJobHandler.Payload(fraction), maxAttempts = 1)
        }
    }
}
