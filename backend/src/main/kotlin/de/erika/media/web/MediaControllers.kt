package de.erika.media.web

import de.erika.contract.api.PhotosApi
import de.erika.media.AppendResult
import de.erika.media.PhotoKeys
import de.erika.media.PhotoRepository
import de.erika.media.PhotoState
import de.erika.media.PhotoUploads
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.storage.StorageProperties
import de.erika.platform.web.ErikaException
import de.erika.sync.SyncViewer
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * tus uploads of photo files (api/mobile.yaml, tag `mobile-uploads`). Written by hand rather than
 * implementing the generated `MobileUploadsApi`: the generator maps the raw
 * `application/offset+octet-stream` body to a multipart file, which Spring can't read from a
 * plain stream. Paths, headers and answers follow the contract; `MobileContractIT` checks them.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
@RequestMapping("/api/v1/mobile/uploads/photos/{photoId}")
internal class PhotoUploadController(
    private val uploads: PhotoUploads,
) {
    @RequestMapping(method = [RequestMethod.HEAD])
    fun head(
        @PathVariable photoId: UUID,
        @RequestHeader("Tus-Resumable", required = false) tus: String?,
    ): ResponseEntity<Void> {
        checkVersion(tus)
        val progress = uploads.progress(photoId, viewer())
        return ResponseEntity
            .ok()
            .header(TUS, VERSION)
            .header("Upload-Offset", progress.offset.toString())
            .header("Upload-Length", progress.length.toString())
            .cacheControl(CacheControl.noStore())
            .build()
    }

    @RequestMapping(method = [RequestMethod.PATCH])
    fun patch(
        @PathVariable photoId: UUID,
        @RequestHeader("Tus-Resumable", required = false) tus: String?,
        @RequestHeader("Upload-Offset", required = false) offset: Long?,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        checkVersion(tus)
        val viewer = viewer()
        if (offset == null ||
            offset < 0
        ) {
            throw ErikaException("UPLOAD_OFFSET_REQUIRED", HttpStatus.BAD_REQUEST, "Upload-Offset is missing.")
        }
        // The photo's existence is checked first (404 for others' photos), then the body's type.
        uploads.progress(photoId, viewer)
        if (request.contentType?.substringBefore(';')?.trim() != CONTENT_TYPE) {
            throw ErikaException("UNSUPPORTED_MEDIA_TYPE", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Send $CONTENT_TYPE.")
        }
        return when (val result = uploads.append(photoId, viewer, offset, request.inputStream)) {
            is AppendResult.Appended -> {
                ResponseEntity
                    .noContent()
                    .header(TUS, VERSION)
                    .header("Upload-Offset", result.progress.offset.toString())
                    .build()
            }

            AppendResult.ChecksumMismatch -> {
                val status = HttpStatusCode.valueOf(460)
                ResponseEntity
                    .status(status)
                    .header(TUS, VERSION)
                    .header("Upload-Offset", "0")
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(
                        ProblemDetail.forStatusAndDetail(status, "The file doesn't match its SHA-256; upload it again.").apply {
                            title = "Checksum Mismatch"
                            setProperty("code", "CHECKSUM_MISMATCH")
                        },
                    )
            }
        }
    }

    private fun checkVersion(tus: String?) {
        if (tus !=
            VERSION
        ) {
            throw ErikaException("TUS_VERSION_UNSUPPORTED", HttpStatus.PRECONDITION_FAILED, "Tus-Resumable must be $VERSION.")
        }
    }

    private fun viewer(): SyncViewer {
        val p = ErikaPrincipal.current()
        return SyncViewer(p.userId, p.workspaceId, p.role, checkNotNull(p.deviceId) { "upload without a device" }, canSeePrices = false)
    }

    private companion object {
        const val TUS = "Tus-Resumable"
        const val VERSION = "1.0.0"
        const val CONTENT_TYPE = "application/offset+octet-stream"
    }
}

/**
 * Photos in the office (api/openapi.yaml, tag `photos`): the thumbnail for the report page and
 * the file as a download. Every web role may read; only STORED (scanned) photos are served.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class PhotosController(
    private val photos: PhotoRepository,
    private val storage: ObjectStorage,
    private val props: StorageProperties,
) : PhotosApi {
    override fun getPhotoThumbnail(photoId: UUID): ResponseEntity<Resource> = serve(photoId, thumbnail = true)

    override fun getPhotoFile(photoId: UUID): ResponseEntity<Resource> = serve(photoId, thumbnail = false)

    private fun serve(
        photoId: UUID,
        thumbnail: Boolean,
    ): ResponseEntity<Resource> {
        val photo =
            photos.find(photoId)?.takeIf { !it.deleted && it.state == PhotoState.STORED }
                ?: throw ErikaException("PHOTO_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")
        val key = if (thumbnail) PhotoKeys.thumbnail(photo) else PhotoKeys.stored(photo)
        val bytes = storage.get(props.mediaBucket, key) ?: throw ErikaException("PHOTO_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")
        val response =
            ResponseEntity
                .ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
        if (!thumbnail) {
            response.header(
                "Content-Disposition",
                ContentDisposition
                    .attachment()
                    .filename("foto-${photo.id}.jpg")
                    .build()
                    .toString(),
            )
        }
        return response.body(ByteArrayResource(bytes))
    }
}
