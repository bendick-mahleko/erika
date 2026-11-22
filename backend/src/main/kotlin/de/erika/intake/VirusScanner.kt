package de.erika.intake

import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Result of a virus scan. [Infected.signature] is the name of the malware, never file content. */
sealed interface ScanResult {
    data object Clean : ScanResult

    data class Infected(
        val signature: String,
    ) : ScanResult
}

/** NF-04: every received file is scanned before it is archived or parsed. */
fun interface VirusScanner {
    /** Throws if the scanner can't give an answer; intake then fails and retries (fail closed). */
    fun scan(bytes: ByteArray): ScanResult
}

class VirusScanUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * ClamAV over clamd's TCP protocol (`zINSTREAM`): the file is streamed in chunks, each
 * prefixed with its length, and clamd answers `stream: OK` or `stream: <name> FOUND`.
 */
@Component
class ClamAvScanner(
    private val props: IntakeProperties,
) : VirusScanner {
    override fun scan(bytes: ByteArray): ScanResult {
        val reply =
            try {
                Socket().use { socket ->
                    val timeout =
                        props.clamav.timeout
                            .toMillis()
                            .toInt()
                    socket.connect(InetSocketAddress(props.clamav.host, props.clamav.port), timeout)
                    socket.soTimeout = timeout
                    val out = DataOutputStream(socket.getOutputStream().buffered())
                    out.write("zINSTREAM\u0000".toByteArray(Charsets.US_ASCII))
                    var offset = 0
                    while (offset < bytes.size) {
                        val length = minOf(CHUNK, bytes.size - offset)
                        out.writeInt(length)
                        out.write(bytes, offset, length)
                        offset += length
                    }
                    out.writeInt(0)
                    out.flush()
                    readReply(socket)
                }
            } catch (e: IOException) {
                throw VirusScanUnavailableException("ClamAV at ${props.clamav.host}:${props.clamav.port} is not reachable", e)
            }
        return parse(reply)
    }

    private fun readReply(socket: Socket): String {
        val buffer = ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (buffer.size() <= MAX_REPLY) {
            val b = input.read()
            if (b <= 0) break
            buffer.write(b)
        }
        return buffer.toString(Charsets.US_ASCII).trim()
    }

    companion object {
        private const val CHUNK = 64 * 1024
        private const val MAX_REPLY = 4096

        internal fun parse(reply: String): ScanResult =
            when {
                reply.endsWith(" FOUND") -> ScanResult.Infected(reply.removePrefix("stream:").removeSuffix(" FOUND").trim())
                reply == "stream: OK" -> ScanResult.Clean
                else -> throw VirusScanUnavailableException("Unexpected ClamAV reply: ${reply.take(200)}")
            }
    }
}
