package de.erika.audit

import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.tenancy.TenantContext
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.HexFormat
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@ConfigurationProperties("erika.audit")
data class AuditProperties(
    /** When the daily chain check is queued (Spring cron, Europe/Berlin). */
    val verifyCron: String = "0 30 3 * * *",
    /** Bucket for the exported head hashes: the backup bucket, outside the main database. */
    val headBucket: String = "erika-backups",
    val headPrefix: String = "audit-heads/",
)

/** Head of one workspace's chain: the latest sequence number and its hash. */
internal data class ChainHead(
    val workspaceId: UUID,
    val seq: Long,
    val hash: String,
)

internal data class ChainProblem(
    val workspaceId: UUID,
    val seq: Long,
    val reason: String,
)

internal data class ChainCheckResult(
    val heads: List<ChainHead>,
    val problems: List<ChainProblem>,
) {
    val ok get() = problems.isEmpty()
}

/**
 * Verifies every workspace's audit chain (NF-10, Software Design §7):
 * 1. Recomputes each hash from the previous hash and the row's content, and checks that
 *    sequence numbers run 1, 2, 3 … without gaps.
 * 2. Compares with the head hashes exported on the previous run, so removing the newest
 *    rows (which leaves a shorter but internally valid chain) is detected too.
 * 3. Exports today's head hashes to the backup bucket.
 *
 * A broken chain is logged at ERROR (→ GlitchTip alert) and counted in the metric
 * `erika.audit.chain.broken` (→ Prometheus alert). The job then fails, so it is visible
 * in the job table as well.
 */
@Component
internal class AuditChainVerifier(
    private val jdbc: JdbcClient,
    private val tenant: TenantContext,
    private val storage: ObjectStorage,
    private val props: AuditProperties,
    private val json: JsonMapper,
    meters: MeterRegistry,
    private val clock: Clock = Clock.system(BERLIN),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val brokenChains = meters.gauge("erika.audit.chain.broken", AtomicInteger(0))!!
    private val checks = meters.counter("erika.audit.chain.checks")

    /** Must run in a transaction (SET LOCAL per workspace). */
    @Transactional
    fun verifyAll(): ChainCheckResult {
        val workspaceIds: List<UUID> =
            jdbc
                .sql("SELECT * FROM all_workspace_ids()")
                .query {
                    rs,
                    _,
                    ->
                    rs.getObject(1, UUID::class.java)
                }.list()
        val previous = latestExport()
        val heads = mutableListOf<ChainHead>()
        val problems = mutableListOf<ChainProblem>()
        for (ws in workspaceIds) {
            tenant.enterWorkspace(ws)
            val rows = loadChain(ws)
            problems += verifyChain(ws, rows)
            rows.lastOrNull()?.let { heads += ChainHead(ws, it.entry.seq, HEX.formatHex(it.hash)) }
            previous[ws]?.let { exported ->
                val row = rows.firstOrNull { it.entry.seq == exported.seq }
                if (row == null || HEX.formatHex(row.hash) != exported.hash) {
                    problems += ChainProblem(ws, exported.seq, "exported head no longer matches")
                }
            }
        }
        checks.increment()
        brokenChains.set(problems.map { it.workspaceId }.distinct().size)
        problems.forEach { log.error("Audit chain broken: workspace {} at seq {} ({})", it.workspaceId, it.seq, it.reason) }
        return ChainCheckResult(heads, problems)
    }

    /** Writes the head hashes to the backup bucket, one object per day. */
    fun exportHeads(heads: List<ChainHead>) {
        val key = "${props.headPrefix}${LocalDate.now(clock)}.json"
        val body = mapOf("exportedAt" to OffsetDateTime.now(clock).toString(), "heads" to heads.sortedBy { it.workspaceId })
        storage.put(props.headBucket, key, json.writeValueAsBytes(body), "application/json")
        log.info("Exported {} audit chain heads to {}/{}", heads.size, props.headBucket, key)
    }

    private fun verifyChain(
        ws: UUID,
        rows: List<StoredEntry>,
    ): List<ChainProblem> {
        var prev = AuditEntry.GENESIS
        rows.forEachIndexed { index, row ->
            val expectedSeq = index + 1L
            when {
                row.entry.seq != expectedSeq -> return listOf(ChainProblem(ws, expectedSeq, "sequence gap"))
                !row.prevHash.contentEquals(prev) -> return listOf(ChainProblem(ws, row.entry.seq, "previous hash mismatch"))
                !row.entry.hash(prev).contentEquals(row.hash) -> return listOf(ChainProblem(ws, row.entry.seq, "content hash mismatch"))
            }
            prev = row.hash
        }
        return emptyList()
    }

    private fun loadChain(ws: UUID): List<StoredEntry> =
        jdbc
            .sql(
                """
                SELECT seq, at, actor, action, payload::text AS payload, prev_hash, hash
                  FROM audit_log WHERE workspace_id = :ws ORDER BY seq
                """.trimIndent(),
            ).param("ws", ws)
            .query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                val details = json.readValue(rs.getString("payload"), Map::class.java) as Map<String, Any?>
                StoredEntry(
                    entry =
                        AuditEntry(
                            workspaceId = ws,
                            seq = rs.getLong("seq"),
                            at = rs.getObject("at", OffsetDateTime::class.java).toInstant(),
                            actor = rs.getObject("actor", UUID::class.java),
                            action = rs.getString("action"),
                            details = TreeMap(details.mapValues { it.value?.toString() ?: "" }),
                        ),
                    prevHash = rs.getBytes("prev_hash"),
                    hash = rs.getBytes("hash"),
                )
            }.list()

    private fun latestExport(): Map<UUID, ChainHead> {
        val key = storage.list(props.headBucket, props.headPrefix).lastOrNull() ?: return emptyMap()
        val bytes = storage.get(props.headBucket, key) ?: return emptyMap()
        val tree = json.readTree(bytes)
        return tree.path("heads").associate {
            val ws = UUID.fromString(it.path("workspaceId").asString())
            ws to ChainHead(ws, it.path("seq").asLong(), it.path("hash").asString())
        }
    }

    private class StoredEntry(
        val entry: AuditEntry,
        val prevHash: ByteArray,
        val hash: ByteArray,
    )

    companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        private val HEX: HexFormat = HexFormat.of()
    }
}

class AuditChainBrokenException(
    count: Int,
) : RuntimeException("Audit chain check found $count problem(s); see the error log")

/** System job (no workspace): verifies all chains, exports heads, fails if a chain is broken. */
@Component
internal class AuditChainCheckJobHandler(
    private val verifier: AuditChainVerifier,
) : JobHandler<AuditChainCheckJobHandler.Payload> {
    data class Payload(
        val requestedAt: String = "",
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val result = verifier.verifyAll()
        // Export only what was verified; a broken chain keeps the last good export as reference.
        if (!result.ok) throw AuditChainBrokenException(result.problems.size)
        verifier.exportHeads(result.heads)
    }

    companion object {
        const val TYPE = "audit.verify-chains"
    }
}

/** Queues the daily check in the worker. Idempotent: one queued check at a time. */
@Component
@Profile(RuntimeInstance.PROFILE_WORKER)
internal class AuditChainCheckScheduler(
    private val jobs: JobQueue,
    private val jdbc: JdbcClient,
) {
    @Scheduled(cron = "\${erika.audit.verify-cron:0 30 3 * * *}", zone = "Europe/Berlin")
    @Transactional
    fun schedule() {
        val pending =
            jdbc
                .sql("SELECT count(*) FROM job WHERE type = :type AND state IN ('QUEUED', 'RUNNING')")
                .param("type", AuditChainCheckJobHandler.TYPE)
                .query(Long::class.java)
                .single()
        if (pending == 0L) {
            jobs.enqueue(
                AuditChainCheckJobHandler.TYPE,
                AuditChainCheckJobHandler.Payload(OffsetDateTime.now().toString()),
                maxAttempts = 1,
            )
        }
    }
}
