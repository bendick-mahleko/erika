package de.erika.media

import de.erika.platform.audit.AuditableEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * PENDING_FILE: the record is there, the file is on its way; UPLOADED: complete and hash-checked,
 * waiting for the virus scan; STORED: scanned, in the media bucket with a thumbnail; REJECTED: a
 * virus or not a JPEG image.
 */
enum class PhotoState { PENDING_FILE, UPLOADED, STORED, REJECTED }

/** A photo of a report (M-MO-05). */
data class Photo(
    val id: UUID,
    val workspaceId: UUID,
    val reportId: UUID,
    val authorId: UUID,
    val caption: String?,
    val category: String,
    val checklistItemKey: String?,
    val takenAt: OffsetDateTime,
    val sizeBytes: Long,
    val sha256: String,
    val state: PhotoState,
    val rejectReason: String?,
    val uploadOffset: Long,
    val width: Int?,
    val height: Int?,
    val deleted: Boolean,
)

/** A photo was created, changed, uploaded, stored, rejected, deleted or restored; only which one is audited. */
data class PhotosChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val id: UUID,
    val change: String,
) : AuditableEvent {
    override val action = "media.photo.$change"
    override val details get() = mapOf("id" to id.toString())
}

/** Where the files live: parts and the assembled upload in the uploads bucket, the rest in the media bucket. */
internal object PhotoKeys {
    fun partPrefix(p: Photo) = "photos/${p.workspaceId}/${p.id}/parts/"

    fun part(
        p: Photo,
        offset: Long,
    ) = "${partPrefix(p)}${offset.toString().padStart(12, '0')}"

    fun upload(p: Photo) = "photos/${p.workspaceId}/${p.id}/upload.jpg"

    fun stored(p: Photo) = "photos/${p.workspaceId}/${p.id}/photo.jpg"

    fun thumbnail(p: Photo) = "photos/${p.workspaceId}/${p.id}/thumb.jpg"
}

private const val WS = "workspace_id = current_workspace_id()"

/** The `media` module's table (V16). Runs in the caller's tenant transaction. */
@Repository
internal class PhotoRepository(
    private val jdbc: JdbcClient,
) {
    fun find(id: UUID): Photo? = findAll(listOf(id)).firstOrNull()

    fun findAll(ids: Collection<UUID>): List<Photo> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("SELECT * FROM photo WHERE id IN (:ids) AND $WS")
                .param("ids", ids)
                .query(::map)
                .list()
        }

    /** The row, locked until commit (one upload request per photo at a time). */
    fun lock(id: UUID): Photo? =
        jdbc
            .sql("SELECT * FROM photo WHERE id = :id AND $WS FOR UPDATE")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun allIds(): List<UUID> =
        jdbc
            .sql("SELECT id FROM photo WHERE $WS ORDER BY id")
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    fun idsOfReports(reportIds: Collection<UUID>): List<UUID> =
        if (reportIds.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("SELECT id FROM photo WHERE report_id IN (:ids) AND $WS")
                .param("ids", reportIds)
                .query(UUID::class.java)
                .list()
                .filterNotNull()
        }

    fun ofReport(reportId: UUID): List<Photo> =
        jdbc
            .sql("SELECT * FROM photo WHERE report_id = :r AND deleted_at IS NULL AND $WS ORDER BY taken_at, id")
            .param("r", reportId)
            .query(::map)
            .list()

    fun insert(p: Photo) {
        jdbc
            .sql(
                """
                INSERT INTO photo (id, workspace_id, report_id, author_id, caption, category, checklist_item_key, taken_at,
                                   size_bytes, sha256)
                VALUES (:id, :ws, :report, :author, :caption, :category, :key, :taken, :size, :sha)
                """.trimIndent(),
            ).param("id", p.id)
            .param("ws", p.workspaceId)
            .param("report", p.reportId)
            .param("author", p.authorId)
            .param("caption", p.caption)
            .param("category", p.category)
            .param("key", p.checklistItemKey)
            .param("taken", p.takenAt)
            .param("size", p.sizeBytes)
            .param("sha", p.sha256)
            .update()
    }

    fun updateDetails(p: Photo) {
        jdbc
            .sql(
                "UPDATE photo SET caption = :caption, category = :category, checklist_item_key = :key, updated_at = now() WHERE id = :id AND $WS",
            ).param("id", p.id)
            .param("caption", p.caption)
            .param("category", p.category)
            .param("key", p.checklistItemKey)
            .update()
    }

    fun setOffset(
        id: UUID,
        offset: Long,
    ) {
        jdbc
            .sql("UPDATE photo SET upload_offset = :o, updated_at = now() WHERE id = :id AND $WS")
            .param("o", offset)
            .param("id", id)
            .update()
    }

    fun setState(
        id: UUID,
        state: PhotoState,
        rejectReason: String? = null,
        width: Int? = null,
        height: Int? = null,
    ) {
        jdbc
            .sql(
                """
                UPDATE photo SET state = :state, reject_reason = :reason, width = coalesce(:w, width), height = coalesce(:h, height),
                       upload_offset = CASE WHEN :state = 'PENDING_FILE' THEN 0 ELSE upload_offset END, updated_at = now()
                 WHERE id = :id AND $WS
                """.trimIndent(),
            ).param("state", state.name)
            .param("reason", rejectReason)
            .param("w", width)
            .param("h", height)
            .param("id", id)
            .update()
    }

    fun markDeleted(
        id: UUID,
        deleted: Boolean,
    ) {
        jdbc
            .sql("UPDATE photo SET deleted_at = CASE WHEN :deleted THEN now() END, updated_at = now() WHERE id = :id AND $WS")
            .param("deleted", deleted)
            .param("id", id)
            .update()
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = Photo(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        reportId = rs.getObject("report_id", UUID::class.java),
        authorId = rs.getObject("author_id", UUID::class.java),
        caption = rs.getString("caption"),
        category = rs.getString("category"),
        checklistItemKey = rs.getString("checklist_item_key"),
        takenAt = rs.getObject("taken_at", OffsetDateTime::class.java),
        sizeBytes = rs.getLong("size_bytes"),
        sha256 = rs.getString("sha256"),
        state = PhotoState.valueOf(rs.getString("state")),
        rejectReason = rs.getString("reject_reason"),
        uploadOffset = rs.getLong("upload_offset"),
        width = rs.getObject("width") as Int?,
        height = rs.getObject("height") as Int?,
        deleted = rs.getObject("deleted_at") != null,
    )
}
