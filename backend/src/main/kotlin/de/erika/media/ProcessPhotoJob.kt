package de.erika.media

import de.erika.intake.ScanResult
import de.erika.intake.VirusScanner
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.storage.StorageProperties
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

data class ProcessPhoto(
    val photoId: UUID,
)

/**
 * Worker job for a complete upload (ADR 0040): checks the hash again, scans for viruses (fail
 * closed: an unreachable scanner fails the job, which is retried), checks that the file is a
 * JPEG image, stores it with a thumbnail in the media bucket and sets STORED; a virus or a
 * non-image sets REJECTED. Idempotent: only an UPLOADED photo is processed.
 */
@Component
internal class ProcessPhotoJob(
    private val photos: PhotoRepository,
    private val storage: ObjectStorage,
    private val props: StorageProperties,
    private val scanner: VirusScanner,
    private val events: ApplicationEventPublisher,
) : JobHandler<ProcessPhoto> {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type = TYPE
    override val payloadType = ProcessPhoto::class.java

    override fun handle(
        payload: ProcessPhoto,
        context: JobContext,
    ) {
        val photo = photos.find(payload.photoId) ?: return
        if (photo.state != PhotoState.UPLOADED) return
        val bytes = storage.get(props.uploadsBucket, PhotoKeys.upload(photo))
        if (bytes == null || PhotoUploads.sha256(bytes) != photo.sha256) {
            // Lost (the uploads bucket's expiry) or damaged: the phone uploads it again.
            photos.setState(photo.id, PhotoState.PENDING_FILE)
            events.publishEvent(PhotosChanged(photo.workspaceId, null, photo.id, "upload_lost"))
            return
        }
        val scan = scanner.scan(bytes)
        if (scan is ScanResult.Infected) {
            log.warn("Photo {} rejected: virus {}", photo.id, scan.signature)
            reject(photo, "VIRUS")
            return
        }
        val image = decodeJpeg(bytes)
        if (image == null) {
            reject(photo, "NOT_AN_IMAGE")
            return
        }
        storage.put(props.mediaBucket, PhotoKeys.stored(photo), bytes, "image/jpeg")
        storage.put(props.mediaBucket, PhotoKeys.thumbnail(photo), thumbnail(image), "image/jpeg")
        storage.delete(props.uploadsBucket, PhotoKeys.upload(photo))
        photos.setState(photo.id, PhotoState.STORED, width = image.width, height = image.height)
        events.publishEvent(PhotosChanged(photo.workspaceId, null, photo.id, "stored"))
    }

    private fun reject(
        photo: Photo,
        reason: String,
    ) {
        storage.delete(props.uploadsBucket, PhotoKeys.upload(photo))
        photos.setState(photo.id, PhotoState.REJECTED, rejectReason = reason)
        events.publishEvent(PhotosChanged(photo.workspaceId, null, photo.id, "rejected"))
    }

    companion object {
        const val TYPE = "media.process-photo"

        /** Long side of thumbnails. */
        const val THUMBNAIL = 400

        /** A JPEG (by its signature) that decodes, or null. */
        fun decodeJpeg(bytes: ByteArray): BufferedImage? {
            if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte() || bytes[2] != 0xFF.toByte()) return null
            return runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        }

        fun thumbnail(image: BufferedImage): ByteArray {
            val scale = minOf(1.0, THUMBNAIL.toDouble() / max(image.width, image.height))
            val w = max(1, (image.width * scale).roundToInt())
            val h = max(1, (image.height * scale).roundToInt())
            val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            small.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(image, 0, 0, w, h, null)
                dispose()
            }
            val out = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param =
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = 0.8f
                    }
                writer.write(null, IIOImage(small, null, null), param)
            }
            writer.dispose()
            return out.toByteArray()
        }
    }
}
