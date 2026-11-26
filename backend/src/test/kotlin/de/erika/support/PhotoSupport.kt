package de.erika.support

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random

/** Test photos and the phone's side of the tus upload (api/mobile.yaml, ADR 0040). */
object TestPhotos {
    /** A JPEG of [width] × [height] with noise, so it doesn't compress to nothing. */
    fun jpeg(
        width: Int = 1200,
        height: Int = 900,
        seed: Int = 1,
        quality: Float = 0.8f,
    ): ByteArray {
        val random = Random(seed)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        for (y in 0 until height step 8) {
            for (x in 0 until width step 8) {
                g.color = Color(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                g.fillRect(x, y, 8, 8)
            }
        }
        g.dispose()
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param =
                writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** The photo record's values for a sync CREATE. */
    fun record(
        reportId: String,
        bytes: ByteArray,
        sha256: String = sha256(bytes),
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        mapOf(
            "reportId" to reportId,
            "category" to "BEFORE",
            "caption" to "Vorher",
            "takenAt" to "2027-06-01T08:00:00Z",
            "sizeBytes" to bytes.size,
            "sha256" to sha256,
        ) + extra
}

/** The tus client of the app, minimal: HEAD for the offset, PATCH chunk by chunk. */
class TestTus(
    private val phone: MobileClient,
) {
    fun path(photoId: Any) = "/api/v1/mobile/uploads/photos/$photoId"

    fun head(photoId: Any) = phone.send("HEAD", path(photoId), null, arrayOf("Tus-Resumable" to "1.0.0"))

    fun offset(photoId: Any): Long = head(photoId).header("Upload-Offset")!!.toLong()

    fun patch(
        photoId: Any,
        offset: Long,
        chunk: ByteArray,
    ) = phone.send(
        "PATCH",
        path(photoId),
        chunk,
        arrayOf("Tus-Resumable" to "1.0.0", "Upload-Offset" to offset.toString(), "Content-Type" to "application/offset+octet-stream"),
    )

    /**
     * Uploads [bytes] from the server's offset on, in [chunk]-sized pieces. Returns the status of
     * the last PATCH (204 when complete, 460 on a checksum mismatch).
     */
    fun upload(
        photoId: Any,
        bytes: ByteArray,
        chunk: Int = 256 * 1024,
    ): Int {
        var offset = offset(photoId)
        var status = 204
        while (offset < bytes.size) {
            val end = minOf(bytes.size.toLong(), offset + chunk).toInt()
            val r = patch(photoId, offset, bytes.copyOfRange(offset.toInt(), end))
            status = r.status
            when (r.status) {
                204 -> offset = r.header("Upload-Offset")!!.toLong()
                409 -> offset = offset(photoId)
                else -> return r.status
            }
        }
        return status
    }

    fun newId(): String = UUID.randomUUID().toString()
}
