package de.erika.media

import de.erika.platform.jobs.JobQueue
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.storage.StorageProperties
import de.erika.platform.tenancy.TenantContext
import de.erika.platform.web.ErikaException
import de.erika.reports.ReportDirectory
import de.erika.sync.SyncViewer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** How far an upload is: tus `Upload-Offset` and `Upload-Length`. */
data class UploadProgress(
    val offset: Long,
    val length: Long,
)

/** What a `PATCH` did. */
sealed interface AppendResult {
    data class Appended(
        val progress: UploadProgress,
    ) : AppendResult

    /** The complete file didn't match the announced SHA-256: the parts are gone, the offset is 0. */
    data object ChecksumMismatch : AppendResult
}

/**
 * Resumable photo uploads with the tus core protocol (ADR 0040; api/mobile.yaml). S3 can't append
 * to an object, so every chunk is stored as a part under the upload's prefix in the uploads
 * bucket, named by its offset; the row's `upload_offset` (locked per request) says how far the
 * upload is. The last chunk assembles the parts, checks the SHA-256 the phone announced and
 * queues the virus scan.
 *
 * Each chunk runs in a transaction of its own: the request's transaction rolls back on every
 * error answer (`TenantTransactionFilter`), but a checksum mismatch (460) must still commit the
 * reset to offset 0, or the phone would resume onto parts that are gone.
 */
@Service
internal class PhotoUploads(
    private val photos: PhotoRepository,
    private val reports: ReportDirectory,
    private val storage: ObjectStorage,
    private val props: StorageProperties,
    private val jobs: JobQueue,
    private val events: ApplicationEventPublisher,
    private val tenant: TenantContext,
    transactionManager: PlatformTransactionManager,
) {
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    @Transactional(propagation = Propagation.MANDATORY)
    fun progress(
        photoId: UUID,
        viewer: SyncViewer,
    ): UploadProgress {
        val photo = visible(photos.find(photoId), viewer)
        return UploadProgress(if (photo.state == PhotoState.PENDING_FILE) photo.uploadOffset else photo.sizeBytes, photo.sizeBytes)
    }

    fun append(
        photoId: UUID,
        viewer: SyncViewer,
        offset: Long,
        body: InputStream,
    ): AppendResult =
        checkNotNull(
            ownTransaction.execute {
                tenant.enterWorkspace(viewer.workspaceId)
                tenant.enterUser(viewer.userId)
                appendLocked(photoId, viewer, offset, body)
            },
        )

    private fun appendLocked(
        photoId: UUID,
        viewer: SyncViewer,
        offset: Long,
        body: InputStream,
    ): AppendResult {
        val photo = visible(photos.lock(photoId), viewer)
        // A chunk of a complete upload (a resend after a lost answer) changes nothing.
        if (photo.state != PhotoState.PENDING_FILE) return AppendResult.Appended(UploadProgress(photo.sizeBytes, photo.sizeBytes))
        if (offset != photo.uploadOffset) {
            throw ErikaException(
                "UPLOAD_OFFSET_MISMATCH",
                HttpStatus.CONFLICT,
                "The upload continues at ${photo.uploadOffset}.",
            ).also { it.body.setProperty("offset", photo.uploadOffset) }
        }
        val chunk = readAtMost(body, MAX_CHUNK)
        if (chunk.size > MAX_CHUNK || offset + chunk.size > photo.sizeBytes) {
            throw ErikaException("UPLOAD_TOO_LARGE", HttpStatus.CONTENT_TOO_LARGE, "Chunks are at most 2 MB and end at the announced size.")
        }
        if (chunk.isEmpty()) return AppendResult.Appended(UploadProgress(offset, photo.sizeBytes))
        storage.put(props.uploadsBucket, PhotoKeys.part(photo, offset), chunk, "application/octet-stream")
        val reached = offset + chunk.size
        photos.setOffset(photo.id, reached)
        if (reached < photo.sizeBytes) return AppendResult.Appended(UploadProgress(reached, photo.sizeBytes))
        return complete(photo)
    }

    /** All parts there and the hash right: the file is assembled and the scan queued. */
    private fun complete(photo: Photo): AppendResult {
        val parts = storage.list(props.uploadsBucket, PhotoKeys.partPrefix(photo))
        val byOffset = parts.associateBy { it.substringAfterLast('/').toLong() }
        val file = ByteArrayOutputStream(photo.sizeBytes.toInt())
        var position = 0L
        while (position < photo.sizeBytes) {
            // A part can be missing when the uploads bucket's expiry removed it (a phone offline for days).
            val bytes = byOffset[position]?.let { storage.get(props.uploadsBucket, it) } ?: break
            file.write(bytes)
            position += bytes.size
        }
        val assembled = file.toByteArray()
        val ok = position == photo.sizeBytes && sha256(assembled) == photo.sha256
        parts.forEach { storage.delete(props.uploadsBucket, it) }
        if (!ok) {
            photos.setOffset(photo.id, 0)
            return AppendResult.ChecksumMismatch
        }
        storage.put(props.uploadsBucket, PhotoKeys.upload(photo), assembled, "image/jpeg")
        photos.setState(photo.id, PhotoState.UPLOADED)
        events.publishEvent(PhotosChanged(photo.workspaceId, photo.authorId, photo.id, "uploaded"))
        jobs.enqueue(ProcessPhotoJob.TYPE, ProcessPhoto(photo.id), photo.workspaceId)
        return AppendResult.Appended(UploadProgress(photo.sizeBytes, photo.sizeBytes))
    }

    private fun visible(
        photo: Photo?,
        viewer: SyncViewer,
    ): Photo {
        if (photo == null || photo.deleted || photo.reportId !in reports.visibleTo(listOf(photo.reportId), viewer)) {
            throw ErikaException("PHOTO_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")
        }
        return photo
    }

    private fun readAtMost(
        input: InputStream,
        max: Int,
    ): ByteArray = input.readNBytes(max + 1)

    companion object {
        /** tus chunks of about 1 MB; up to 2 MB accepted. */
        const val MAX_CHUNK = 2 * 1024 * 1024

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
