package de.erika.documents

import de.erika.platform.audit.AuditableEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

internal data class CommentRow(
    val id: UUID,
    val documentId: UUID,
    val authorId: UUID,
    val body: String,
    val createdAt: OffsetDateTime,
)

/** A comment was added; the text itself is not audited (it may contain invoice content). */
data class DocumentCommented(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val documentId: UUID,
    val commentId: UUID,
) : AuditableEvent {
    override val action = "document.comment.added"
    override val details get() = mapOf("documentId" to documentId.toString(), "commentId" to commentId.toString())
}

@Repository
internal class CommentRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        id: UUID,
        workspaceId: UUID,
        documentId: UUID,
        authorId: UUID,
        body: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO document_comment (id, workspace_id, document_id, author_id, body)
                VALUES (:id, :ws, :doc, :author, :body)
                """.trimIndent(),
            ).param("id", id)
            .param("ws", workspaceId)
            .param("doc", documentId)
            .param("author", authorId)
            .param("body", body)
            .update()
    }

    fun find(id: UUID): CommentRow? =
        jdbc
            .sql("$SELECT WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun forDocument(documentId: UUID): List<CommentRow> =
        jdbc
            .sql("$SELECT WHERE document_id = :doc AND workspace_id = current_workspace_id() ORDER BY created_at, id")
            .param("doc", documentId)
            .query { rs, _ -> map(rs) }
            .list()

    private fun map(rs: java.sql.ResultSet) =
        CommentRow(
            id = rs.getObject("id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            authorId = rs.getObject("author_id", UUID::class.java),
            body = rs.getString("body"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        )

    private companion object {
        const val SELECT = "SELECT id, document_id, author_id, body, created_at FROM document_comment"
    }
}

/**
 * Comments on documents by the team and the tax advisor (EX-02). Append-only: they can't be
 * edited or deleted, and they never touch the original.
 */
@Service
internal class Comments(
    private val repository: CommentRepository,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun add(
        workspaceId: UUID,
        documentId: UUID,
        authorId: UUID,
        body: String,
    ): UUID {
        val id = UUID.randomUUID()
        repository.insert(id, workspaceId, documentId, authorId, body.trim())
        events.publishEvent(DocumentCommented(workspaceId, authorId, documentId, id))
        return id
    }
}
