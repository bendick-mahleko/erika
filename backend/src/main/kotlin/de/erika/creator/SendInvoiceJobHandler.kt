package de.erika.creator

import de.erika.archive.Archive
import de.erika.documents.DocumentStatus
import de.erika.documents.Documents
import de.erika.identity.WorkspaceProfiles
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.mail.MailProperties
import de.erika.platform.mail.RawMailTransport
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties
import java.util.UUID

/**
 * Sends an issued invoice by email (CR-07), in the worker:
 * 1. build the message (text + the archived invoice file as attachment) with a fixed
 *    Message-ID, date and MIME boundary, so a retry builds the same bytes;
 * 2. archive the message (.eml) write-once and record it on the delivery;
 * 3. send exactly those bytes; mark the delivery SENT and the document SENT.
 * Sending is the last step: if it fails, the transaction rolls back and the job retries.
 * SMTP has no idempotency key, so a crash right after sending can send twice (accepted).
 * After the last attempt the delivery is marked FAILED and the document DELIVERY_FAILED.
 */
@Component
class SendInvoiceJobHandler internal constructor(
    private val deliveries: DeliveryRepository,
    private val issuedRepo: IssuedRepository,
    private val settingsRepo: InvoiceSettingsRepository,
    private val profiles: WorkspaceProfiles,
    private val archive: Archive,
    private val documents: Documents,
    private val transport: RawMailTransport,
    private val mailProps: MailProperties,
    private val events: ApplicationEventPublisher,
    @param:Value("\${erika.mail.enabled:true}") private val enabled: Boolean,
    private val clock: Clock = Clock.system(Creator.BERLIN),
) : JobHandler<SendInvoiceJobHandler.Payload> {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Payload(
        val deliveryId: UUID,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val delivery = deliveries.find(payload.deliveryId) ?: return
        if (delivery.status != DeliveryStatus.QUEUED) return
        val issued = issuedRepo.find(delivery.invoiceId) ?: return
        try {
            send(delivery, issued)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (context.attempt < MAX_ATTEMPTS) throw e
            log.error("Invoice email {} failed after {} attempts", delivery.id, context.attempt, e)
            deliveries.updateStatus(delivery.id, DeliveryStatus.FAILED, "Versand nach $MAX_ATTEMPTS Versuchen fehlgeschlagen")
            documents.changeStatus(delivery.workspaceId, issued.documentId, DocumentStatus.DELIVERY_FAILED, delivery.recipient, null)
            events.publishEvent(InvoiceDeliveryChanged(delivery.workspaceId, issued.id, delivery.id, DeliveryStatus.FAILED))
        }
    }

    private fun send(
        delivery: DeliveryRow,
        issued: IssuedRow,
    ) {
        val file = checkNotNull(archive.open(delivery.workspaceId, issued.sha256)) { "Invoice file of ${issued.id} not in the archive" }
        val eml = message(delivery, issued, file.bytes)
        val stored =
            archive.store(
                delivery.workspaceId,
                eml,
                "message/rfc822",
                issued.issueDate,
                LocalDate.now(clock),
                delivery.requestedBy,
            )
        if (!enabled) {
            log.info("Mail sending disabled; invoice email {} archived but not sent", delivery.id)
            deliveries.markSent(delivery.id, stored.sha256, null)
        } else {
            val answer = transport.send(eml, listOf(delivery.recipient))
            deliveries.markSent(delivery.id, stored.sha256, providerId(answer))
        }
        val status = documents.statuses(listOf(issued.documentId))[issued.documentId]
        if (status == DocumentStatus.ISSUED || status == DocumentStatus.DELIVERY_FAILED) {
            documents.changeStatus(delivery.workspaceId, issued.documentId, DocumentStatus.SENT, delivery.recipient, delivery.requestedBy)
        }
        events.publishEvent(InvoiceSent(delivery.workspaceId, delivery.requestedBy, issued.id, delivery.id, stored.sha256))
    }

    /** The complete message; the same delivery always gives the same bytes. */
    internal fun message(
        delivery: DeliveryRow,
        issued: IssuedRow,
        file: ByteArray,
    ): ByteArray {
        val settings = settingsRepo.find(delivery.workspaceId)
        val seller = profiles.of(delivery.workspaceId)
        val session = Session.getInstance(Properties())
        val message =
            object : MimeMessage(session) {
                override fun updateMessageID() = setHeader("Message-ID", delivery.messageId)
            }
        val from = InternetAddress(mailProps.from)
        message.setFrom(InternetAddress(from.address, seller.name, "UTF-8"))
        settings.contactEmail?.takeIf { it.isNotBlank() }?.let {
            message.replyTo =
                arrayOf(
                    InternetAddress(it.trim(), seller.name, "UTF-8"),
                )
        }
        message.setRecipient(jakarta.mail.Message.RecipientType.TO, InternetAddress(delivery.recipient, true))
        message.setSubject(delivery.subject, "UTF-8")
        message.sentDate = Date.from(delivery.createdAt.toInstant())

        val text = MimeBodyPart().apply { setText(body(delivery, issued, seller.name), "UTF-8") }
        val attachment =
            MimeBodyPart().apply {
                dataHandler = jakarta.activation.DataHandler(ByteArrayDataSource(file, issued.mediaType))
                fileName = issued.fileName
                disposition = jakarta.mail.Part.ATTACHMENT
            }
        val boundary = "erika-${delivery.id}"
        val multipart =
            object : MimeMultipart("mixed") {
                init {
                    contentType = "multipart/mixed; boundary=\"$boundary\""
                }
            }
        multipart.addBodyPart(text)
        multipart.addBodyPart(attachment)
        message.setContent(multipart)
        message.saveChanges()
        return ByteArrayOutputStream().also(message::writeTo).toByteArray()
    }

    private fun body(
        delivery: DeliveryRow,
        issued: IssuedRow,
        sellerName: String,
    ): String {
        val what =
            when (issued.kind) {
                InvoiceKind.CREDIT_NOTE, InvoiceKind.CANCELLATION -> "unsere Gutschrift"
                InvoiceKind.CORRECTION -> "unsere korrigierte Rechnung"
                InvoiceKind.INVOICE -> "unsere Rechnung"
            }
        val intro =
            delivery.message ?: "Guten Tag,\n\nanbei erhalten Sie $what ${issued.number} vom ${issued.issueDate.format(DATE)}.\n\n" +
                "Mit freundlichen Grüßen\n$sellerName"
        val format =
            when (issued.format.name) {
                "ZUGFERD_EN16931" -> "ZUGFeRD (PDF mit eingebetteten Rechnungsdaten nach EN 16931)"
                else -> "XRechnung (XML nach EN 16931)"
            }
        return "$intro\n\n--\nDie Rechnung ist eine elektronische Rechnung im Format $format.\n"
    }

    /** "250 2.0.0 Ok: queued as 4ABC123" → "4ABC123"; otherwise the answer without the status code. */
    internal fun providerId(answer: String?): String? {
        val a = answer?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        QUEUED_AS.find(a)?.let { return it.groupValues[1] }
        return a.replace(Regex("^\\d{3}[ -](\\d\\.\\d\\.\\d+ )?"), "").take(200).ifBlank { null }
    }

    companion object {
        const val TYPE = "creator.send-invoice"
        const val MAX_ATTEMPTS = 6
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private val QUEUED_AS = Regex("queued as ([A-Za-z0-9._@<>-]+)", RegexOption.IGNORE_CASE)
    }
}
