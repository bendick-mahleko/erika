package de.erika.audit

import de.erika.platform.audit.AuditableEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.UUID

/**
 * One audit entry. Its hash covers the previous entry's hash and a canonical text of
 * every field, so changing, inserting or removing any row breaks the chain (NF-10).
 */
internal data class AuditEntry(
    val workspaceId: UUID,
    val seq: Long,
    val at: Instant,
    val actor: UUID?,
    val action: String,
    val details: Map<String, String>,
) {
    /**
     * Canonical form: JSON with sorted keys, no whitespace, written by a fixed mapper that
     * no application setting can change. Changing this format breaks every existing chain.
     */
    fun canonical(): String =
        CANONICAL_JSON.writeValueAsString(
            TreeMap<String, Any?>().apply {
                put("action", action)
                put("actor", actor?.toString())
                put("at", DateTimeFormatter.ISO_INSTANT.format(at))
                put("details", TreeMap(details))
                put("seq", seq)
                put("workspace", workspaceId.toString())
            },
        )

    fun hash(prevHash: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(prevHash)
            update(canonical().toByteArray(Charsets.UTF_8))
            digest()
        }

    companion object {
        /** prev_hash of the first entry of every chain. */
        val GENESIS = ByteArray(32)

        private val CANONICAL_JSON: JsonMapper = JsonMapper.builder().build()
    }
}

/**
 * Appends to a workspace's chain. Runs in the caller's transaction, so the audit entry
 * commits if and only if the change it records commits. A transaction-scoped advisory
 * lock per workspace serialises writers, so sequence numbers have no gaps or forks.
 */
@Component
internal class AuditLogWriter(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun append(event: AuditableEvent) {
        withWorkspace(event.workspaceId) {
            jdbc
                .sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", lockKey(event.workspaceId))
                .query(Any::class.java)
                .single()
            val last =
                jdbc
                    .sql("SELECT seq, hash FROM audit_log WHERE workspace_id = :ws ORDER BY seq DESC LIMIT 1")
                    .param("ws", event.workspaceId)
                    .query { rs, _ -> rs.getLong("seq") to rs.getBytes("hash") }
                    .optional()
                    .orElse(null)
            val prevHash = last?.second ?: AuditEntry.GENESIS
            val entry =
                AuditEntry(
                    workspaceId = event.workspaceId,
                    seq = (last?.first ?: 0) + 1,
                    at = Instant.now().truncatedTo(ChronoUnit.MICROS),
                    actor = event.actorId,
                    action = event.action,
                    details = event.details,
                )
            jdbc
                .sql(
                    """
                    INSERT INTO audit_log (id, workspace_id, seq, at, actor, action, payload, prev_hash, hash)
                    VALUES (:id, :ws, :seq, :at, :actor, :action, CAST(:payload AS jsonb), :prevHash, :hash)
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("ws", entry.workspaceId)
                .param("seq", entry.seq)
                .param("at", OffsetDateTime.ofInstant(entry.at, java.time.ZoneOffset.UTC))
                .param("actor", entry.actor, Types.OTHER)
                .param("action", entry.action)
                .param("payload", json.writeValueAsString(entry.details))
                .param("prevHash", prevHash)
                .param("hash", entry.hash(prevHash))
                .update()
        }
    }

    /**
     * Scopes RLS to the event's workspace for the insert and restores the previous scope,
     * so recording an event for another workspace (e.g. a failed sign-in of a user with
     * several workspaces) doesn't change the caller's tenant scope.
     */
    private fun withWorkspace(
        workspaceId: UUID,
        block: () -> Unit,
    ) {
        val previous =
            jdbc
                .sql("SELECT current_setting('app.workspace_id', true)")
                .query(String::class.java)
                .optional()
                .orElse(null)
        setWorkspace(workspaceId.toString())
        try {
            block()
        } finally {
            setWorkspace(previous ?: "")
        }
    }

    private fun setWorkspace(value: String) {
        jdbc
            .sql("SELECT set_config('app.workspace_id', :v, true)")
            .param("v", value)
            .query(String::class.java)
            .single()
    }

    private fun lockKey(workspaceId: UUID): Long = workspaceId.mostSignificantBits xor workspaceId.leastSignificantBits xor LOCK_NAMESPACE

    private companion object {
        /** Keeps audit advisory locks apart from any other advisory lock users. */
        const val LOCK_NAMESPACE = 0x41554449544C4F47L // "AUDITLOG"
    }
}

/**
 * Listener for all auditable domain events (Software Design §3: audit records what the
 * other modules publish). Synchronous, inside the publisher's transaction.
 */
@Component
internal class AuditEventListener(
    private val writer: AuditLogWriter,
) {
    @EventListener
    fun on(event: AuditableEvent) = writer.append(event)
}
