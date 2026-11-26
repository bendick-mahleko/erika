package de.erika.sync

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** A field's version (sequence number of its last change) and the hash of its value. */
internal data class FieldState(
    val version: Long,
    val hash: String,
)

internal data class ChangeRow(
    val entity: String,
    val id: UUID,
    val seq: Long,
    val fields: Map<String, FieldState>,
)

internal data class ConflictNote(
    val id: UUID,
    val entity: String,
    val entityId: UUID,
    val field: String,
    val serverValue: String?,
    val deviceValue: String?,
    val winner: String,
    val userId: UUID,
    val deviceId: UUID,
    val createdAt: OffsetDateTime? = null,
)

/** The `sync` module's tables (V14). Runs in the caller's tenant transaction. */
@Repository
internal class SyncRepository(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) {
    /**
     * Reserves [count] sequence numbers and returns the first. Locks the workspace's counter
     * row until commit, so sequence order is commit order (ADR 0038).
     */
    fun reserve(count: Int): Long {
        require(count >= 1)
        val last =
            jdbc
                .sql(
                    """
                    INSERT INTO sync_counter (workspace_id, value) VALUES (current_workspace_id(), :n)
                    ON CONFLICT (workspace_id) DO UPDATE SET value = sync_counter.value + :n
                    RETURNING value
                    """.trimIndent(),
                ).param("n", count)
                .query(Long::class.java)
                .single()
        return last - count + 1
    }

    /** Locks the counter row (creating it) and tells whether the first-sync bootstrap ran. */
    fun lockBootstrapped(): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO sync_counter (workspace_id, value) VALUES (current_workspace_id(), 0)
                ON CONFLICT (workspace_id) DO UPDATE SET value = sync_counter.value
                RETURNING bootstrapped
                """.trimIndent(),
            ).query(Boolean::class.java)
            .single()

    fun currentWorkspace(): UUID? =
        jdbc
            .sql("SELECT current_workspace_id()")
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    fun markBootstrapped() {
        jdbc.sql("UPDATE sync_counter SET bootstrapped = true WHERE workspace_id = current_workspace_id()").update()
    }

    fun fields(
        entity: String,
        ids: Collection<UUID>,
    ): Map<UUID, Map<String, FieldState>> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql(
                    "SELECT entity_id, fields::text FROM change_log " +
                        "WHERE workspace_id = current_workspace_id() AND entity = :entity AND entity_id IN (:ids)",
                ).param("entity", entity)
                .param("ids", ids)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to parseFields(rs.getString(2)) }
                .list()
                .toMap()
        }

    fun row(
        entity: String,
        id: UUID,
    ): ChangeRow? =
        jdbc
            .sql(
                "SELECT entity, entity_id, seq, fields::text FROM change_log " +
                    "WHERE workspace_id = current_workspace_id() AND entity = :entity AND entity_id = :id",
            ).param("entity", entity)
            .param("id", id)
            .query { rs, _ ->
                ChangeRow(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getLong(3), parseFields(rs.getString(4)))
            }.optional()
            .orElse(null)

    fun upsert(
        entity: String,
        id: UUID,
        seq: Long,
        fields: Map<String, FieldState>,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO change_log (workspace_id, entity, entity_id, seq, fields)
                VALUES (current_workspace_id(), :entity, :id, :seq, CAST(:fields AS jsonb))
                ON CONFLICT (workspace_id, entity, entity_id)
                DO UPDATE SET seq = EXCLUDED.seq, fields = EXCLUDED.fields, changed_at = now()
                """.trimIndent(),
            ).param("entity", entity)
            .param("id", id)
            .param("seq", seq)
            .param("fields", writeFields(fields))
            .update()
    }

    fun count(entity: String): Int =
        jdbc
            .sql("SELECT count(*) FROM change_log WHERE workspace_id = current_workspace_id() AND entity = :entity")
            .param("entity", entity)
            .query(Int::class.java)
            .single()

    /** Gives every record of [entity] a new sequence number from [firstSeq] on (fields unchanged). */
    fun touchAll(
        entity: String,
        firstSeq: Long,
    ) {
        jdbc
            .sql(
                """
                UPDATE change_log c SET seq = :first + n.rn - 1, changed_at = now()
                  FROM (SELECT entity_id, row_number() OVER (ORDER BY seq, entity_id) AS rn FROM change_log
                         WHERE workspace_id = current_workspace_id() AND entity = :entity) n
                 WHERE c.workspace_id = current_workspace_id() AND c.entity = :entity AND c.entity_id = n.entity_id
                """.trimIndent(),
            ).param("first", firstSeq)
            .param("entity", entity)
            .update()
    }

    /** Changes after [cursor] in sequence order; [limit] + 1 rows tell whether there are more. */
    fun since(
        cursor: Long,
        entities: Collection<String>,
        limit: Int,
    ): List<ChangeRow> =
        jdbc
            .sql(
                """
                SELECT entity, entity_id, seq, fields::text FROM change_log
                 WHERE workspace_id = current_workspace_id() AND seq > :cursor AND entity IN (:entities)
                 ORDER BY seq
                 LIMIT :limit
                """.trimIndent(),
            ).param("cursor", cursor)
            .param("entities", entities)
            .param("limit", limit)
            .query { rs, _ ->
                ChangeRow(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getLong(3), parseFields(rs.getString(4)))
            }.list()

    fun appliedResult(mutationId: UUID): String? =
        jdbc
            .sql("SELECT result::text FROM applied_mutation WHERE workspace_id = current_workspace_id() AND mutation_id = :id")
            .param("id", mutationId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun recordApplied(
        mutationId: UUID,
        deviceId: UUID,
        result: String,
    ) {
        jdbc
            .sql(
                "INSERT INTO applied_mutation (workspace_id, mutation_id, device_id, result) " +
                    "VALUES (current_workspace_id(), :id, :device, CAST(:result AS jsonb))",
            ).param("id", mutationId)
            .param("device", deviceId)
            .param("result", result)
            .update()
    }

    /** Forgets this device's mutations older than 30 days (ADR 0038). */
    fun forgetOldMutations(deviceId: UUID) {
        jdbc
            .sql(
                "DELETE FROM applied_mutation WHERE workspace_id = current_workspace_id() AND device_id = :device " +
                    "AND created_at < now() - interval '30 days'",
            ).param("device", deviceId)
            .update()
    }

    fun insertConflict(c: ConflictNote) {
        jdbc
            .sql(
                """
                INSERT INTO sync_conflict (id, workspace_id, entity, entity_id, field, server_value, device_value, winner, user_id, device_id)
                VALUES (:id, current_workspace_id(), :entity, :entityId, :field, :server, :device, :winner, :user, :deviceId)
                """.trimIndent(),
            ).param("id", c.id)
            .param("entity", c.entity)
            .param("entityId", c.entityId)
            .param("field", c.field)
            .param("server", c.serverValue)
            .param("device", c.deviceValue)
            .param("winner", c.winner)
            .param("user", c.userId)
            .param("deviceId", c.deviceId)
            .update()
    }

    fun conflicts(entityId: UUID): List<ConflictNote> =
        jdbc
            .sql(
                "SELECT * FROM sync_conflict WHERE workspace_id = current_workspace_id() AND entity_id = :id " +
                    "ORDER BY created_at DESC, id LIMIT 200",
            ).param("id", entityId)
            .query { rs, _ ->
                ConflictNote(
                    id = rs.getObject("id", UUID::class.java),
                    entity = rs.getString("entity"),
                    entityId = rs.getObject("entity_id", UUID::class.java),
                    field = rs.getString("field"),
                    serverValue = rs.getString("server_value"),
                    deviceValue = rs.getString("device_value"),
                    winner = rs.getString("winner"),
                    userId = rs.getObject("user_id", UUID::class.java),
                    deviceId = rs.getObject("device_id", UUID::class.java),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                )
            }.list()

    private fun parseFields(text: String): Map<String, FieldState> =
        json.readValue(text, object : TypeReference<Map<String, List<Any>>>() {}).mapValues { (_, v) ->
            FieldState((v[0] as Number).toLong(), v[1] as String)
        }

    private fun writeFields(fields: Map<String, FieldState>): String =
        json.writeValueAsString(fields.toSortedMap().mapValues { (_, f) -> listOf(f.version, f.hash) })
}
