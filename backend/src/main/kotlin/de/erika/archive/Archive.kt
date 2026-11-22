package de.erika.archive

import de.erika.platform.audit.AuditableEvent
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.web.ErikaException
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/** One original in the archive (published API of the `archive` module). */
data class StoredObject(
    val workspaceId: UUID,
    val sha256: String,
    val sizeBytes: Long,
    val mediaType: String,
    val storageKey: String,
    val versionId: String?,
    val storedAt: Instant,
    val retentionUntil: LocalDate,
    val objectLock: ObjectLock,
    val legalHold: Boolean,
)

enum class ObjectLock {
    /** S3 Object Lock in COMPLIANCE mode until the retention date (preferred, ADR 0013). */
    COMPLIANCE,

    /** Provider without Object Lock: protected only by bucket permissions (fallback). */
    NONE,
}

class ArchivedContent(
    val stored: StoredObject,
    val bytes: ByteArray,
)

@ConfigurationProperties("erika.archive")
data class ArchiveProperties(
    /** COMPLIANCE where the provider supports Object Lock; NONE is the documented fallback. */
    val objectLock: ObjectLock = ObjectLock.COMPLIANCE,
    /** Share of originals re-hashed by the weekly check (all of them once a year). */
    val weeklySampleFraction: Double = 0.01,
    val weeklyCron: String = "0 0 4 * * SUN",
    val yearlyCron: String = "0 30 4 5 1 *",
)

/** The stored bytes no longer match their SHA-256: the original is damaged or was replaced. */
class ArchiveIntegrityException(
    sha256: String,
) : ErikaException(
        "ARCHIVE_INTEGRITY_FAILED",
        HttpStatus.INTERNAL_SERVER_ERROR,
        "The stored original failed its integrity check (${sha256.take(12)}…). The operations team has been alerted.",
    )

data class ObjectArchived(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val sha256: String,
    val retentionUntil: LocalDate,
    val objectLock: ObjectLock,
) : AuditableEvent {
    override val action = "archive.object.stored"
    override val details
        get() = mapOf("sha256" to sha256, "retentionUntil" to retentionUntil.toString(), "objectLock" to objectLock.name)
}

data class LegalHoldChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val sha256: String,
    val legalHold: Boolean,
) : AuditableEvent {
    override val action = "archive.legal_hold.changed"
    override val details get() = mapOf("sha256" to sha256, "legalHold" to legalHold.toString())
}

data class IntegrityCheckFailed(
    override val workspaceId: UUID,
    val sha256: String,
    val reason: String,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "archive.integrity.failed"
    override val details get() = mapOf("sha256" to sha256, "reason" to reason)
}

/**
 * Write-once storage of originals (AR-01, AR-02, BR-02, BR-03, Software Design §5/§7):
 * - key `ws/<workspace>/<sha256>`; storing the same bytes twice in a workspace is a no-op,
 * - S3 Object Lock (COMPLIANCE) until the retention date; the row names the exact version,
 * - every [open] recomputes the SHA-256 and refuses damaged content,
 * - the app role can neither delete rows nor change their identity (DB grants).
 *
 * All methods run in the caller's transaction, which must be scoped to the workspace
 * (TenantContext); row-level security rejects anything else.
 */
@Service
class Archive(
    private val jdbc: JdbcClient,
    private val storage: ObjectStorage,
    private val events: ApplicationEventPublisher,
    private val props: ArchiveProperties,
    meters: MeterRegistry,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val integrityFailures = meters.counter("erika.archive.integrity.failures")
    private val separateTx =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /**
     * Stores [content] unless the workspace already has it; returns the stored object.
     * [documentDate] is the issue date when known; [receivedOn] the day of receipt.
     * A repeated store with a later date extends the retention, never shortens it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun store(
        workspaceId: UUID,
        content: ByteArray,
        mediaType: String,
        documentDate: LocalDate?,
        receivedOn: LocalDate,
        actorId: UUID? = null,
    ): StoredObject {
        val sha256 = sha256Hex(content)
        val retention = RetentionPolicy.retentionUntil(documentDate, receivedOn)
        find(workspaceId, sha256)?.let { existing -> return extendIfLater(existing, retention) }

        val key = "ws/$workspaceId/$sha256"
        val retainUntil = if (props.objectLock == ObjectLock.COMPLIANCE) endOfDayUtc(retention) else null
        val version = storage.putOriginal(key, content, mediaType, retainUntil)
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO stored_object (workspace_id, sha256, size_bytes, media_type, storage_key, version_id,
                                               retention_until, object_lock)
                    VALUES (:ws, :sha, :size, :mediaType, :key, :version, :retention, :lock)
                    ON CONFLICT (workspace_id, sha256) DO NOTHING
                    """.trimIndent(),
                ).param("ws", workspaceId)
                .param("sha", sha256)
                .param("size", content.size.toLong())
                .param("mediaType", mediaType)
                .param("key", key)
                .param("version", version.versionId)
                .param("retention", retention)
                .param("lock", props.objectLock.name)
                .update()
        // A concurrent store of the same bytes won the race: its row stands; our extra
        // version is locked as well and simply never referenced.
        val stored = find(workspaceId, sha256)!!
        if (inserted == 1) events.publishEvent(ObjectArchived(workspaceId, actorId, sha256, retention, props.objectLock))
        return stored
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun find(
        workspaceId: UUID,
        sha256: String,
    ): StoredObject? =
        jdbc
            .sql("$SELECT WHERE workspace_id = :ws AND sha256 = :sha")
            .param("ws", workspaceId)
            .param("sha", sha256)
            .query(::map)
            .optional()
            .orElse(null)

    /** Reads the original and verifies its SHA-256 (Software Design §7: checked on every download). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun open(
        workspaceId: UUID,
        sha256: String,
    ): ArchivedContent? {
        val stored = find(workspaceId, sha256) ?: return null
        val problem = verify(stored)
        if (problem != null) {
            recordIntegrityFailure(stored, problem)
            throw ArchiveIntegrityException(sha256)
        }
        return ArchivedContent(stored, storage.getOriginal(stored.storageKey, stored.versionId)!!)
    }

    /** Legal hold (e.g. tax audit, litigation): the deletion job (later) skips held objects. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun setLegalHold(
        workspaceId: UUID,
        sha256: String,
        legalHold: Boolean,
        actorId: UUID?,
    ) {
        val changed =
            jdbc
                .sql("UPDATE stored_object SET legal_hold = :hold WHERE workspace_id = :ws AND sha256 = :sha AND legal_hold <> :hold")
                .param("hold", legalHold)
                .param("ws", workspaceId)
                .param("sha", sha256)
                .update()
        if (changed == 1) events.publishEvent(LegalHoldChanged(workspaceId, actorId, sha256, legalHold))
    }

    /**
     * Re-hashes one stored original. Returns null if intact, else the reason. Marks the
     * check time when intact. Used by [open] and the scheduled integrity check.
     */
    internal fun verify(stored: StoredObject): String? {
        val bytes = storage.getOriginal(stored.storageKey, stored.versionId) ?: return "object missing"
        if (sha256Hex(bytes) != stored.sha256) return "content hash mismatch"
        jdbc
            .sql("UPDATE stored_object SET last_verified_at = now() WHERE workspace_id = :ws AND sha256 = :sha")
            .param("ws", stored.workspaceId)
            .param("sha", stored.sha256)
            .update()
        return null
    }

    /** Logged at ERROR (→ alert), counted, and audited in its own transaction so it survives a rollback. */
    internal fun recordIntegrityFailure(
        stored: StoredObject,
        reason: String,
    ) {
        integrityFailures.increment()
        log.error("Archive integrity check failed: workspace {} object {} ({})", stored.workspaceId, stored.sha256, reason)
        separateTx.executeWithoutResult {
            jdbc
                .sql("SELECT set_config('app.workspace_id', :ws, true)")
                .param("ws", stored.workspaceId.toString())
                .query(String::class.java)
                .single()
            events.publishEvent(IntegrityCheckFailed(stored.workspaceId, stored.sha256, reason))
        }
    }

    private fun extendIfLater(
        existing: StoredObject,
        retention: LocalDate,
    ): StoredObject {
        if (!retention.isAfter(existing.retentionUntil)) return existing
        if (existing.objectLock == ObjectLock.COMPLIANCE) {
            storage.extendRetention(existing.storageKey, existing.versionId, endOfDayUtc(retention))
        }
        jdbc
            .sql("UPDATE stored_object SET retention_until = :retention WHERE workspace_id = :ws AND sha256 = :sha")
            .param("retention", retention)
            .param("ws", existing.workspaceId)
            .param("sha", existing.sha256)
            .update()
        return existing.copy(retentionUntil = retention)
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = StoredObject(
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        sha256 = rs.getString("sha256"),
        sizeBytes = rs.getLong("size_bytes"),
        mediaType = rs.getString("media_type"),
        storageKey = rs.getString("storage_key"),
        versionId = rs.getString("version_id"),
        storedAt = rs.getObject("stored_at", OffsetDateTime::class.java).toInstant(),
        retentionUntil = rs.getObject("retention_until", LocalDate::class.java),
        objectLock = ObjectLock.valueOf(rs.getString("object_lock")),
        legalHold = rs.getBoolean("legal_hold"),
    )

    companion object {
        private const val SELECT =
            "SELECT workspace_id, sha256, size_bytes, media_type, storage_key, version_id, stored_at, retention_until, " +
                "object_lock, legal_hold FROM stored_object"

        fun sha256Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** The lock covers the whole last day of retention. */
        fun endOfDayUtc(date: LocalDate): Instant = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
    }
}
