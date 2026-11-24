package de.erika.intake

import de.erika.archive.Archive
import de.erika.documents.Documents
import de.erika.documents.ReceivedVia
import de.erika.identity.WorkspaceContacts
import de.erika.platform.mail.Mail
import de.erika.platform.mail.MailProperties
import de.erika.platform.mail.Outbox
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The common part of upload and email intake (Software Design §5, Flow A steps 3–4), for
 * files that arrived and are about to be imported:
 * - ZIPs are unpacked within the limits (depth 2, 50 files, zip-bomb checks);
 * - every file is virus-scanned (NF-04) before anything else happens to it;
 * - only XML and PDF are accepted; each is archived write-once and registered as a document,
 *   which queues reading and validation in the same transaction.
 *
 * Runs in the job's transaction, scoped to the workspace: if anything fails, nothing is
 * recorded and the job retries from the start (archive writes are idempotent by content).
 */
@Component
internal class IntakePipeline(
    private val archive: Archive,
    private val documents: Documents,
    private val scanner: VirusScanner,
    private val repository: IntakeRepository,
    private val events: ApplicationEventPublisher,
    private val contacts: WorkspaceContacts,
    private val outbox: Outbox,
    private val messages: MessageSource,
    private val mailProps: MailProperties,
    private val props: IntakeProperties,
    private val clock: Clock = Clock.system(BERLIN),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zip = ZipUnpacker(props.zip, props.maxFileBytes)

    fun importFiles(
        intake: IntakeRow,
        files: List<IncomingFile>,
        via: ReceivedVia,
    ): List<ItemResult> =
        files.flatMap { file ->
            if (!ZipUnpacker.isZip(file.bytes)) return@flatMap listOf(importFile(intake, file, via))
            try {
                val unpacked = zip.unpack(file.name, file.bytes)
                unpacked.refused.map { ItemResult(it.name, IntakeOutcome.REJECTED, it.reason) } +
                    unpacked.files.map { importFile(intake, it, via) }
            } catch (e: ZipRejectedException) {
                log.info("Intake {}: ZIP refused ({})", intake.id, e.reason)
                listOf(ItemResult(file.name, IntakeOutcome.REJECTED, e.reason, sizeBytes = file.bytes.size.toLong()))
            }
        }

    /** Stores the items, sets the final state, audits, and notifies members who asked for it. */
    fun complete(
        intake: IntakeRow,
        items: List<ItemResult>,
        status: IntakeStatus,
        reason: IntakeReason?,
    ) {
        repository.insertItems(intake.workspaceId, intake.id, items)
        repository.finish(intake.id, status, reason)
        val imported = items.count { it.outcome == IntakeOutcome.IMPORTED }
        events.publishEvent(
            IntakeProcessed(
                workspaceId = intake.workspaceId,
                actorId = intake.createdBy,
                intakeId = intake.id,
                source = intake.source,
                imported = imported,
                alreadyInArchive = items.count { it.outcome == IntakeOutcome.ALREADY_IN_ARCHIVE },
                rejected = items.count { it.outcome == IntakeOutcome.REJECTED },
                reason = reason,
            ),
        )
        if (imported > 0) notify(intake, imported)
    }

    private fun importFile(
        intake: IntakeRow,
        file: IncomingFile,
        via: ReceivedVia,
    ): ItemResult {
        val size = file.bytes.size.toLong()

        fun rejected(reason: IntakeReason) = ItemResult(file.name, IntakeOutcome.REJECTED, reason, sizeBytes = size)
        if (size == 0L) return rejected(IntakeReason.EMPTY_FILE)
        if (size > props.maxFileBytes) return rejected(IntakeReason.FILE_TOO_LARGE)
        when (val scan = scanner.scan(file.bytes)) {
            is ScanResult.Infected -> {
                log.warn("Intake {}: virus found ({}); file not imported", intake.id, scan.signature)
                events.publishEvent(VirusDetected(intake.workspaceId, intake.id, scan.signature))
                return rejected(IntakeReason.VIRUS_FOUND)
            }

            ScanResult.Clean -> {
                Unit
            }
        }
        val mediaType = mediaTypeOf(file.bytes) ?: return rejected(IntakeReason.UNSUPPORTED_FILE_TYPE)
        val stored = archive.store(intake.workspaceId, file.bytes, mediaType, null, LocalDate.now(clock), intake.createdBy)
        val registration = documents.registerIncoming(stored, displayName(file.name), via, intake.createdBy)
        return ItemResult(
            fileName = file.name,
            outcome = if (registration.created) IntakeOutcome.IMPORTED else IntakeOutcome.ALREADY_IN_ARCHIVE,
            documentId = registration.documentId,
            sizeBytes = size,
            sha256 = stored.sha256,
        )
    }

    /** Email to members who asked for it; the uploader doesn't need to hear about their own upload. */
    private fun notify(
        intake: IntakeRow,
        imported: Int,
    ) {
        contacts
            .newDocumentRecipients(intake.workspaceId)
            .filter { it.userId != intake.createdBy }
            .forEach { contact ->
                val locale = Locale.forLanguageTag(contact.locale)
                val channel = messages.getMessage("mail.newdocuments.via.${intake.source.name.lowercase()}", null, locale)
                outbox.send(
                    Mail(
                        to = contact.email,
                        subject = messages.getMessage("mail.newdocuments.subject", null, locale),
                        body =
                            messages.getMessage(
                                "mail.newdocuments.body",
                                arrayOf<Any>(contact.displayName, imported, channel, "${mailProps.webBaseUrl}/inbox"),
                                locale,
                            ),
                    ),
                )
            }
    }

    companion object {
        private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

        /** Content decides, not the name: PDF (`%PDF-`) or XML (`<` after an optional BOM and whitespace). */
        fun mediaTypeOf(bytes: ByteArray): String? {
            if (bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-") return "application/pdf"
            var i = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
            if (i >= bytes.size || bytes[i] != '<'.code.toByte()) return null
            // Active web content is not an invoice, even when it is well-formed XML (NF-04, ASVS 12.2).
            return if (ACTIVE_CONTENT.containsMatchIn(
                    String(bytes, i, minOf(bytes.size - i, HEAD), Charsets.ISO_8859_1),
                )
            ) {
                null
            } else {
                "application/xml"
            }
        }

        /** HTML, SVG or script as the document element (after declaration, comments and doctype). */
        private val ACTIVE_CONTENT =
            Regex(
                """^(?:<\?xml[^>]*\?>\s*)?(?:<!--.*?-->\s*)*(?:<!DOCTYPE\s+(?:html|svg)\b|<(?:[\w-]+:)?(?:html|svg|script)\b)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        private const val HEAD = 4096

        /** The last path segment, without control characters: "rechnungen.zip/2026/re-1.xml" → "re-1.xml". */
        fun displayName(path: String): String =
            path
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filterNot { it.isISOControl() }
                .take(MAX_NAME)
                .ifBlank { "datei" }

        private const val MAX_NAME = 255
    }
}
