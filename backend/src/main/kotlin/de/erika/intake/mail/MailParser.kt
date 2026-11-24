package de.erika.intake.mail

import de.erika.intake.IncomingFile
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayInputStream
import java.util.Properties

/**
 * Finds the attachments of a received email that may be invoices: XML, PDF and ZIP files,
 * by media type or file name. Message bodies, signatures and inline images are ignored.
 * Forwarded emails (message/rfc822 parts) are searched too, a few levels deep.
 */
internal object MailParser {
    private const val MAX_DEPTH = 6
    private const val MAX_FILES = 50

    private val acceptedTypes =
        setOf(
            "application/xml",
            "text/xml",
            "application/pdf",
            "application/x-pdf",
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
        )
    private val acceptedExtensions = listOf(".xml", ".pdf", ".zip")

    /** From header and subject (decoded), for the quarantine list (IN-06). Null when missing or unreadable. */
    fun headers(raw: ByteArray): Pair<String?, String?> =
        runCatching {
            val message = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))
            val from = message.getHeader("From", null)?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
            from to message.subject
        }.getOrDefault(null to null)

    fun attachments(raw: ByteArray): List<IncomingFile> {
        // A session without any server: only used to parse.
        val message = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw))
        val files = mutableListOf<IncomingFile>()
        collect(message, files, depth = 0)
        return files
    }

    private fun collect(
        part: Part,
        files: MutableList<IncomingFile>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || files.size >= MAX_FILES) return
        when {
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as Multipart
                for (i in 0 until multipart.count) collect(multipart.getBodyPart(i), files, depth + 1)
            }

            part.isMimeType("message/rfc822") -> {
                (part.content as? Part)?.let { collect(it, files, depth + 1) }
            }

            else -> {
                val name = fileName(part)
                val type = baseType(part)
                val wanted = type in acceptedTypes || acceptedExtensions.any { name?.lowercase()?.endsWith(it) == true }
                // An octet-stream without a name is usually not a document.
                if (wanted && !(type == "application/octet-stream" && name == null)) {
                    files += IncomingFile(name ?: defaultName(type, files.size + 1), part.inputStream.use { it.readAllBytes() })
                }
            }
        }
    }

    private fun fileName(part: Part): String? =
        runCatching { part.fileName?.let(MimeUtility::decodeText) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun baseType(part: Part): String = runCatching { ContentType(part.contentType).baseType.lowercase() }.getOrDefault("")

    private fun defaultName(
        type: String,
        n: Int,
    ): String =
        when {
            type.endsWith("pdf") -> "anhang-$n.pdf"
            type.endsWith("zip") || type.endsWith("zip-compressed") -> "anhang-$n.zip"
            else -> "anhang-$n.xml"
        }
}
