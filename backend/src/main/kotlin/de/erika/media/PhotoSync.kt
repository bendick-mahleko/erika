package de.erika.media

import de.erika.archive.Archive
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.storage.StorageProperties
import de.erika.reports.ReportDirectory
import de.erika.reports.ReportMedia
import de.erika.reports.ReportPhotoInfo
import de.erika.sync.ChangeFeed
import de.erika.sync.FieldPolicy
import de.erika.sync.SyncEntity
import de.erika.sync.SyncRejection
import de.erika.sync.SyncViewer
import de.erika.workorders.WorkOrdersChanged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Photos are JPEGs of at most 15 MB (2,048 px at quality 80 is about 1 MB, ADR 0040). */
internal const val MAX_PHOTO_BYTES = 15L * 1024 * 1024

private val SHA256 = Regex("^[0-9a-f]{64}$")
private val CATEGORIES = setOf("BEFORE", "AFTER", "DEFECT", "OTHER")

private fun invalid(field: String) = SyncRejection("INVALID_${field.uppercase()}")

private fun Map<String, Any?>.text(
    field: String,
    max: Int,
): String? =
    when (val v = this[field]) {
        null -> null
        is String -> v.trim().ifEmpty { null }?.also { if (it.length > max) throw invalid(field) }
        else -> throw invalid(field)
    }

/**
 * Photos of a report (M-MO-05; ADR 0038, 0040). The record syncs at once with state
 * PENDING_FILE; the file follows through `/mobile/uploads/photos/{id}`. Visible where the report
 * is; the author (or the office) changes caption, category and checklist item, or deletes it.
 * State, size and hash are the server's.
 */
@Component
internal class PhotoSyncEntity(
    private val photos: PhotoRepository,
    private val reports: ReportDirectory,
    private val events: ApplicationEventPublisher,
) : SyncEntity {
    override val name = NAME

    override val fields =
        listOf("reportId", "caption", "category", "checklistItemKey", "takenAt", "sizeBytes", "sha256")
            .associateWith { FieldPolicy.LAST_WRITER_WINS }

    override fun allIds(): List<UUID> = photos.allIds()

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        photos.findAll(ids).associate { p ->
            p.id to
                linkedMapOf(
                    "reportId" to p.reportId.toString(),
                    "authorId" to p.authorId.toString(),
                    "caption" to p.caption,
                    "category" to p.category,
                    "checklistItemKey" to p.checklistItemKey,
                    "takenAt" to p.takenAt.toInstant().toString(),
                    "sizeBytes" to p.sizeBytes,
                    "sha256" to p.sha256,
                    "state" to p.state.name,
                    "rejectReason" to p.rejectReason,
                    "width" to p.width,
                    "height" to p.height,
                    SyncEntity.DELETED to p.deleted,
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ): Map<String, Any?>? = viewAll(mapOf(UUID(0, 0) to data), viewer).values.first()

    override fun viewAll(
        data: Map<UUID, Map<String, Any?>>,
        viewer: SyncViewer,
    ): Map<UUID, Map<String, Any?>?> {
        val visible = reports.visibleTo(data.values.map { UUID.fromString(it["reportId"] as String) }, viewer)
        return data.mapValues { (_, d) -> d.takeIf { UUID.fromString(d["reportId"] as String) in visible } }
    }

    override fun mayWrite(
        field: String,
        viewer: SyncViewer,
    ) = field == "caption" || field == "category" || field == "checklistItemKey"

    override fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val reportId =
            (values["reportId"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw SyncRejection("REPORT_REQUIRED")
        reports.checkWritable(reportId, viewer)
        val size = (values["sizeBytes"] as? Number)?.toLong() ?: throw invalid("sizeBytes")
        if (size <= 0 || size > MAX_PHOTO_BYTES) throw SyncRejection("PHOTO_TOO_LARGE")
        val sha = (values["sha256"] as? String)?.lowercase()?.takeIf { SHA256.matches(it) } ?: throw invalid("sha256")
        val takenAt =
            (values["takenAt"] as? String)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: throw invalid("takenAt")
        val photo =
            checked(
                Photo(
                    id = id,
                    workspaceId = viewer.workspaceId,
                    reportId = reportId,
                    authorId = viewer.userId,
                    caption = values.text("caption", 500),
                    category = values.text("category", 20) ?: "OTHER",
                    checklistItemKey = values.text("checklistItemKey", 100),
                    takenAt = takenAt,
                    sizeBytes = size,
                    sha256 = sha,
                    state = PhotoState.PENDING_FILE,
                    rejectReason = null,
                    uploadOffset = 0,
                    width = null,
                    height = null,
                    deleted = false,
                ),
            )
        photos.insert(photo)
        events.publishEvent(PhotosChanged(viewer.workspaceId, viewer.userId, id, "created"))
    }

    override fun update(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val current = writable(id, viewer)
        val next =
            checked(
                current.copy(
                    caption = if ("caption" in values) values.text("caption", 500) else current.caption,
                    category = if ("category" in values) values.text("category", 20) ?: throw invalid("category") else current.category,
                    checklistItemKey = if ("checklistItemKey" in values) values.text("checklistItemKey", 100) else current.checklistItemKey,
                ),
            )
        photos.updateDetails(next)
        events.publishEvent(PhotosChanged(viewer.workspaceId, viewer.userId, id, "changed"))
    }

    override fun delete(
        id: UUID,
        viewer: SyncViewer,
    ) {
        writable(id, viewer)
        photos.markDeleted(id, true)
        events.publishEvent(PhotosChanged(viewer.workspaceId, viewer.userId, id, "deleted"))
    }

    override fun restore(
        id: UUID,
        viewer: SyncViewer,
    ) {
        val photo = photos.find(id) ?: throw SyncRejection("NOT_FOUND")
        reports.checkWritable(photo.reportId, viewer)
        photos.markDeleted(id, false)
        events.publishEvent(PhotosChanged(viewer.workspaceId, viewer.userId, id, "restored"))
    }

    private fun writable(
        id: UUID,
        viewer: SyncViewer,
    ): Photo {
        val photo = photos.find(id) ?: throw SyncRejection("NOT_FOUND")
        reports.checkWritable(photo.reportId, viewer)
        if (!viewer.isOffice && photo.authorId != viewer.userId) throw SyncRejection("NOT_AUTHOR")
        return photo
    }

    private fun checked(p: Photo): Photo {
        if (p.category !in CATEGORIES) throw invalid("category")
        p.checklistItemKey?.let { if (it !in reports.checklistKeys(p.reportId)) throw SyncRejection("CHECKLIST_ITEM_UNKNOWN") }
        return p
    }

    companion object {
        const val NAME = "photo"
    }
}

/**
 * Every audited change of a photo reaches the change feed (ADR 0038). A changed work order (e.g.
 * reassigned) sends its report's photos again, like `reports` does for its own records.
 */
@Component
internal class PhotosFeed(
    private val feed: ChangeFeed,
    private val photos: PhotoRepository,
    private val reports: ReportDirectory,
) {
    @EventListener
    fun on(event: PhotosChanged) = feed.changed(PhotoSyncEntity.NAME, event.id)

    @EventListener
    fun on(event: WorkOrdersChanged) {
        if (event.kind != "work_order" || event.created) return
        feed.changedAll(PhotoSyncEntity.NAME, photos.idsOfReports(reports.reportIdsOf(event.id)))
    }
}

/** Photos for the report: checklist evidence (mandatory PHOTO items) and the office's list. */
@Component
internal class PhotoReportMedia(
    private val photos: PhotoRepository,
    private val storage: ObjectStorage,
    private val props: StorageProperties,
    private val archive: Archive,
    private val events: ApplicationEventPublisher,
) : ReportMedia {
    override fun answeredItems(reportId: UUID): Set<String> =
        photos
            .ofReport(reportId)
            .filter { it.state != PhotoState.REJECTED }
            .mapNotNull { it.checklistItemKey }
            .toSet()

    override fun photos(reportId: UUID): List<ReportPhotoInfo> =
        photos.ofReport(reportId).map {
            ReportPhotoInfo(
                id = it.id,
                caption = it.caption,
                category = it.category,
                checklistItemKey = it.checklistItemKey,
                state = it.state.name,
                rejectReason = it.rejectReason,
                takenAt = it.takenAt,
                authorId = it.authorId,
                width = it.width,
                height = it.height,
                sha256 = it.sha256,
            )
        }

    override fun copyPhotos(
        from: UUID,
        to: UUID,
        workspaceId: UUID,
        actorId: UUID,
    ) {
        for (photo in photos.ofReport(from)) {
            val id = UUID.randomUUID()
            photos.insertCopy(photo, id, to)
            events.publishEvent(PhotosChanged(workspaceId, actorId, id, "created"))
        }
    }

    override fun image(
        photoId: UUID,
        thumbnail: Boolean,
    ): ByteArray? {
        val photo = photos.find(photoId)?.takeIf { it.state == PhotoState.STORED } ?: return null
        return storage.get(props.mediaBucket, if (thumbnail) PhotoKeys.thumbnail(photo) else PhotoKeys.stored(photo))
    }

    override fun archive(
        reportId: UUID,
        workspaceId: UUID,
        retainUntil: LocalDate,
    ) {
        for (photo in photos.ofReport(reportId).filter { it.state == PhotoState.STORED }) {
            val bytes = storage.get(props.mediaBucket, PhotoKeys.stored(photo)) ?: continue
            archive.store(workspaceId, bytes, "image/jpeg", null, LocalDate.now(), retainUntil = retainUntil)
        }
    }
}
