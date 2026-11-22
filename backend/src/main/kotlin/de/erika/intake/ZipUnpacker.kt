package de.erika.intake

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** One file to import: the name shown to the user and its bytes. */
class IncomingFile(
    val name: String,
    val bytes: ByteArray,
)

/** A file in a ZIP that is refused on its own (the rest of the ZIP is still imported). */
data class RefusedEntry(
    val name: String,
    val reason: IntakeReason,
)

data class Unpacked(
    val files: List<IncomingFile>,
    val refused: List<RefusedEntry>,
)

/** The whole ZIP is refused: too deep, too many files, a zip bomb, encrypted or damaged. */
class ZipRejectedException(
    val reason: IntakeReason,
) : RuntimeException("ZIP rejected: $reason")

/**
 * Unpacks ZIPs in memory with the limits of Software Design §5 (NF-04): depth, number of
 * files, total size and compression ratio. Nothing is written to disk, so paths inside the
 * archive (`../../etc`) affect only the displayed name.
 */
class ZipUnpacker(
    private val limits: ZipLimits,
    private val maxFileBytes: Long,
) {
    fun unpack(
        name: String,
        bytes: ByteArray,
    ): Unpacked {
        val state = State()
        walk(name, bytes, depth = 1, state)
        return Unpacked(state.files, state.refused)
    }

    private class State {
        val files = mutableListOf<IncomingFile>()
        val refused = mutableListOf<RefusedEntry>()
        var count = 0
        var totalBytes = 0L
    }

    private fun walk(
        zipName: String,
        bytes: ByteArray,
        depth: Int,
        state: State,
    ) {
        if (depth > limits.maxDepth) throw ZipRejectedException(IntakeReason.ZIP_TOO_DEEP)
        val counting = CountingInputStream(ByteArrayInputStream(bytes))
        try {
            ZipInputStream(counting).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || isNoise(entry.name)) continue
                    if (++state.count > limits.maxFiles) throw ZipRejectedException(IntakeReason.ZIP_TOO_MANY_FILES)
                    val path = "$zipName/${entry.name.trimStart('/')}"
                    val compressedBefore = counting.count
                    val content = readLimited(zip, maxFileBytes)
                    val compressed = (counting.count - compressedBefore).coerceAtLeast(1)
                    state.totalBytes += content.size
                    if (state.totalBytes > limits.maxTotalBytes) throw ZipRejectedException(IntakeReason.ZIP_BOMB)
                    val ratio = content.size / compressed
                    when {
                        // Large and highly compressed: a zip bomb, not just a big file.
                        content.size > maxFileBytes && ratio > limits.maxRatio -> throw ZipRejectedException(IntakeReason.ZIP_BOMB)

                        content.size > maxFileBytes -> state.refused += RefusedEntry(path, IntakeReason.FILE_TOO_LARGE)

                        content.size > BOMB_MIN_BYTES && ratio > limits.maxRatio -> throw ZipRejectedException(IntakeReason.ZIP_BOMB)

                        isZip(content) -> walk(path, content, depth + 1, state)

                        else -> state.files += IncomingFile(path, content)
                    }
                }
            }
        } catch (e: ZipRejectedException) {
            throw e
        } catch (e: ZipException) {
            val encrypted = e.message?.contains("encrypt", ignoreCase = true) == true
            throw ZipRejectedException(if (encrypted) IntakeReason.ZIP_ENCRYPTED else IntakeReason.ZIP_DAMAGED)
        } catch (e: IOException) {
            throw ZipRejectedException(IntakeReason.ZIP_DAMAGED)
        } catch (e: IllegalArgumentException) {
            // Entry names in an invalid encoding.
            throw ZipRejectedException(IntakeReason.ZIP_DAMAGED)
        }
    }

    /** Reads at most [limit] + 1 bytes, so an oversized entry is detected without inflating all of it. */
    private fun readLimited(
        input: InputStream,
        limit: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (total <= limit) {
            val want = minOf(buffer.size.toLong(), limit + 1 - total).toInt()
            val n = input.read(buffer, 0, want)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private class CountingInputStream(
        input: InputStream,
    ) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val BOMB_MIN_BYTES = 1024 * 1024

        /** Local file header, or the end record of an empty archive. */
        fun isZip(bytes: ByteArray): Boolean {
            if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
            return (bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) || (bytes[2] == 5.toByte() && bytes[3] == 6.toByte())
        }

        /** macOS resource forks and hidden files (`.DS_Store`) are not documents. */
        private fun isNoise(name: String): Boolean {
            val last = name.trimEnd('/').substringAfterLast('/')
            return name.startsWith("__MACOSX/") || last.startsWith(".")
        }
    }
}
