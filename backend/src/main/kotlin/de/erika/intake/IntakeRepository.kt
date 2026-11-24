package de.erika.intake

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

internal data class IntakeRow(
    val id: UUID,
    val workspaceId: UUID,
    val source: IntakeSource,
    val status: IntakeStatus,
    val reason: IntakeReason?,
    val fileName: String?,
    val declaredSize: Long?,
    val contentType: String?,
    val uploadKey: String?,
    val rawSha256: String?,
    val sender: String?,
    val createdBy: UUID?,
    val createdAt: OffsetDateTime,
    val fromAddress: String? = null,
    val releasedAt: OffsetDateTime? = null,
)

/** Tenant tables `intake`, `intake_item`, `intake_address`; every query runs under RLS. */
@Repository
internal class IntakeRepository(
    private val jdbc: JdbcClient,
) {
    fun insertUpload(
        id: UUID,
        workspaceId: UUID,
        fileName: String,
        size: Long,
        contentType: String,
        uploadKey: String,
        createdBy: UUID,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO intake (id, workspace_id, source, status, file_name, declared_size, content_type, upload_key, created_by)
                VALUES (:id, :ws, 'UPLOAD', 'AWAITING_FILE', :fileName, :size, :contentType, :key, :createdBy)
                """.trimIndent(),
            ).param("id", id)
            .param("ws", workspaceId)
            .param("fileName", fileName)
            .param("size", size)
            .param("contentType", contentType)
            .param("key", uploadKey)
            .param("createdBy", createdBy)
            .update()
    }

    fun insertEmail(
        id: UUID,
        workspaceId: UUID,
        rawSha256: String,
        size: Long,
        sender: String?,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO intake (id, workspace_id, source, status, declared_size, content_type, raw_sha256, sender, received_at)
                VALUES (:id, :ws, 'EMAIL', 'RECEIVED', :size, 'message/rfc822', :sha, :sender, now())
                """.trimIndent(),
            ).param("id", id)
            .param("ws", workspaceId)
            .param("size", size)
            .param("sha", rawSha256)
            .param("sender", sender)
            .update()
    }

    fun find(id: UUID): IntakeRow? =
        jdbc
            .sql("SELECT * FROM intake WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    /** AWAITING_FILE → RECEIVED; false if it was in another state (already completed). */
    fun markReceived(id: UUID): Boolean =
        jdbc
            .sql(
                """
                UPDATE intake SET status = 'RECEIVED', received_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id() AND status = 'AWAITING_FILE'
                """.trimIndent(),
            ).param("id", id)
            .update() == 1

    fun finish(
        id: UUID,
        status: IntakeStatus,
        reason: IntakeReason?,
    ) {
        jdbc
            .sql(
                """
                UPDATE intake SET status = :status, reason = :reason, processed_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id()
                """.trimIndent(),
            ).param("status", status.name)
            .param("reason", reason?.name)
            .param("id", id)
            .update()
    }

    fun insertItems(
        workspaceId: UUID,
        intakeId: UUID,
        items: List<ItemResult>,
    ) {
        items.forEachIndexed { seq, item ->
            jdbc
                .sql(
                    """
                    INSERT INTO intake_item (id, workspace_id, intake_id, seq, file_name, size_bytes, sha256, outcome, reason, document_id)
                    VALUES (:id, :ws, :intake, :seq, :fileName, :size, :sha, :outcome, :reason, :doc)
                    """.trimIndent(),
                ).param("id", UUID.randomUUID())
                .param("ws", workspaceId)
                .param("intake", intakeId)
                .param("seq", seq)
                .param("fileName", item.fileName.take(MAX_NAME))
                .param("size", item.sizeBytes)
                .param("sha", item.sha256)
                .param("outcome", item.outcome.name)
                .param("reason", item.reason?.name)
                .param("doc", item.documentId)
                .update()
        }
    }

    fun items(intakeId: UUID): List<ItemResult> =
        jdbc
            .sql(
                """
                SELECT file_name, outcome, reason, document_id, size_bytes, sha256 FROM intake_item
                 WHERE intake_id = :id AND workspace_id = current_workspace_id() ORDER BY seq
                """.trimIndent(),
            ).param("id", intakeId)
            .query { rs, _ ->
                ItemResult(
                    fileName = rs.getString("file_name"),
                    outcome = IntakeOutcome.valueOf(rs.getString("outcome")),
                    reason = rs.getString("reason")?.let(IntakeReason::valueOf),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    sizeBytes = rs.getLong("size_bytes").takeUnless { rs.wasNull() },
                    sha256 = rs.getString("sha256"),
                )
            }.list()

    fun addressToken(workspaceId: UUID): String? =
        jdbc
            .sql("SELECT token FROM intake_address WHERE workspace_id = :ws")
            .param("ws", workspaceId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun insertAddress(
        workspaceId: UUID,
        token: String,
    ): Boolean =
        jdbc
            .sql("INSERT INTO intake_address (workspace_id, token) VALUES (:ws, :token) ON CONFLICT (workspace_id) DO NOTHING")
            .param("ws", workspaceId)
            .param("token", token)
            .update() == 1

    /** Needs no workspace scope: SECURITY DEFINER lookup of the token's workspace (V4). */
    fun workspaceForToken(token: String): UUID? =
        jdbc
            .sql("SELECT intake_workspace_for_token(:token)")
            .param("token", token)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = IntakeRow(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        source = IntakeSource.valueOf(rs.getString("source")),
        status = IntakeStatus.valueOf(rs.getString("status")),
        reason = rs.getString("reason")?.let(IntakeReason::valueOf),
        fileName = rs.getString("file_name"),
        declaredSize = rs.getLong("declared_size").takeUnless { rs.wasNull() },
        contentType = rs.getString("content_type"),
        uploadKey = rs.getString("upload_key"),
        rawSha256 = rs.getString("raw_sha256"),
        sender = rs.getString("sender"),
        createdBy = rs.getObject("created_by", UUID::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        fromAddress = rs.getString("from_address"),
        releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
    )

    private companion object {
        const val MAX_NAME = 1000
    }
}
