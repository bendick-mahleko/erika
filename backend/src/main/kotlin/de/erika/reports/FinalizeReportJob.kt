package de.erika.reports

import de.erika.archive.Archive
import de.erika.identity.WorkspaceProfiles
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.mail.MailProperties
import de.erika.platform.mail.RawMailTransport
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties
import java.util.UUID

data class FinalizeReport(
    val reportId: UUID,
    /** How often the job already waited for photos still on their way. */
    val round: Int = 0,
)

/**
 * Worker job after a signature (M-MO-11, M-BR-08; ADR 0040): waits until the report's photos
 * have arrived (at most a day), renders the master PDF from the server's data, stores it and
 * the photos write-once in the archive, and emails the PDF to the customer if an address was
 * given; the message is archived and logged in `report_delivery`. Idempotent: the PDF is
 * archived once, the email sent once.
 */
@Component
internal class FinalizeReportJob(
    private val repo: ReportsRepository,
    private val snapshots: ReportSnapshots,
    private val media: ObjectProvider<ReportMedia>,
    private val archive: Archive,
    private val profiles: WorkspaceProfiles,
    private val creator: de.erika.creator.Creator,
    private val mail: MailProperties,
    private val transport: RawMailTransport,
    private val jobs: JobQueue,
    private val events: ApplicationEventPublisher,
    @param:Value("\${erika.mail.enabled:true}") private val mailEnabled: Boolean,
    @param:Value("\${erika.reports.photo-wait:PT30M}") private val photoWait: Duration,
) : JobHandler<FinalizeReport> {
    private val log = LoggerFactory.getLogger(javaClass)
    private val clock: Clock = Clock.systemUTC()

    override val type = TYPE
    override val payloadType = FinalizeReport::class.java

    override fun handle(
        payload: FinalizeReport,
        context: JobContext,
    ) {
        val workspaceId = checkNotNull(context.workspaceId)
        val signature = repo.signatureOf(payload.reportId) ?: return
        val report = repo.reports(listOf(payload.reportId)).firstOrNull() ?: return
        val pdfSha =
            signature.pdfSha256 ?: run {
                val photos = media.orderedStream().toList().flatMap { it.photos(report.id) }
                val pending = photos.count { it.state == "PENDING_FILE" || it.state == "UPLOADED" }
                if (pending > 0 && payload.round < MAX_ROUNDS) {
                    // Photos still on their way from the phone: try again later, up to a day.
                    jobs.enqueue(TYPE, payload.copy(round = payload.round + 1), workspaceId, photoWait)
                    return
                }
                archiveReport(workspaceId, report, signature)
            }
        val email = signature.customerEmail
        if (email != null && repo.deliveryCount(report.id) == 0) send(workspaceId, report, signature, email, pdfSha)
    }

    private fun archiveReport(
        workspaceId: UUID,
        report: Report,
        signature: ReportSignature,
    ): String {
        val raw = snapshots.raw(report)
        val hash = ReportCanonical.hash(raw)
        if (hash != signature.contentHash) {
            // The trigger keeps signed reports unchanged, so this means the canonical form changed.
            log.error("Report {} no longer matches its signed hash; archived with the signed hash", report.id)
        }
        val profile = profiles.of(workspaceId)
        val images =
            media
                .orderedStream()
                .toList()
                .flatMap { m ->
                    m.photos(report.id).filter { it.state == "STORED" }.mapNotNull { p ->
                        m.image(p.id, true)?.let {
                            p.id.toString() to
                                it
                        }
                    }
                }.toMap()
        val pdf =
            ReportPdf.render(
                ReportPdfContent(
                    firmName = profile.name,
                    firmAddress =
                        listOfNotNull(
                            profile.street,
                            listOfNotNull(profile.postalCode, profile.city).joinToString(" ").ifBlank {
                                null
                            },
                        ).joinToString(", ").ifBlank { null },
                    report = raw,
                    contentHash = signature.contentHash,
                    signature = signature,
                    verificationUrl = verificationUrl(signature.verificationCode),
                    photoImages = images,
                    logo = creator.logo(workspaceId)?.first,
                ),
            )
        val signedOn = signature.signedAt.atZoneSameInstant(ZONE).toLocalDate()
        val stored = archive.store(workspaceId, pdf, "application/pdf", signedOn, LocalDate.now(clock))
        // Photos: 5 years after the job by default (warranty for building work, § 634a BGB; ADR 0040).
        media.orderedStream().forEach { it.archive(report.id, workspaceId, signedOn.plusYears(PHOTO_YEARS)) }
        repo.setPdf(signature.id, stored.sha256)
        events.publishEvent(ReportPdfArchived(workspaceId, report.id, stored.sha256))
        return stored.sha256
    }

    private fun send(
        workspaceId: UUID,
        report: Report,
        signature: ReportSignature,
        recipient: String,
        pdfSha: String,
    ) {
        val pdf = checkNotNull(archive.open(workspaceId, pdfSha)) { "report PDF $pdfSha not in the archive" }.bytes
        val profile = profiles.of(workspaceId)
        val eml = message(report, signature, recipient, pdf, profile.name)
        val signedOn = signature.signedAt.atZoneSameInstant(ZONE).toLocalDate()
        val stored = archive.store(workspaceId, eml, "message/rfc822", signedOn, LocalDate.now(clock))
        val answer = if (mailEnabled) transport.send(eml, listOf(recipient)) else null
        repo.insertDelivery(workspaceId, report.id, recipient, stored.sha256, answer?.take(200))
        events.publishEvent(ReportSent(workspaceId, report.id, stored.sha256))
    }

    internal fun message(
        report: Report,
        signature: ReportSignature,
        recipient: String,
        pdf: ByteArray,
        firmName: String,
    ): ByteArray {
        val session = Session.getInstance(Properties())
        val message =
            object : MimeMessage(session) {
                override fun updateMessageID() = setHeader("Message-ID", "<report-${report.id}@erika>")
            }
        val from = InternetAddress(mail.from)
        message.setFrom(InternetAddress(from.address, firmName, "UTF-8"))
        message.setRecipient(jakarta.mail.Message.RecipientType.TO, InternetAddress(recipient, true))
        val date = signature.signedAt.atZoneSameInstant(ZONE).format(DATE)
        message.setSubject("Arbeitsbericht vom $date", "UTF-8")
        message.sentDate = Date.from(clock.instant())
        val what = if (signature.kind == "SIGNED") "den von Ihnen unterschriebenen Arbeitsbericht" else "unseren Arbeitsbericht"
        val text =
            MimeBodyPart().apply {
                setText(
                    "Guten Tag,\n\nanbei erhalten Sie $what vom $date (Version ${report.version}).\n\n" +
                        "Sie können die Echtheit jederzeit prüfen: ${verificationUrl(signature.verificationCode)}\n\n" +
                        "Mit freundlichen Grüßen\n$firmName",
                    "UTF-8",
                )
            }
        val attachment =
            MimeBodyPart().apply {
                dataHandler = jakarta.activation.DataHandler(ByteArrayDataSource(pdf, "application/pdf"))
                fileName = "Arbeitsbericht-${signature.verificationCode}.pdf"
                disposition = jakarta.mail.Part.ATTACHMENT
            }
        message.setContent(
            MimeMultipart("mixed").apply {
                addBodyPart(text)
                addBodyPart(attachment)
            },
        )
        message.saveChanges()
        return ByteArrayOutputStream().also(message::writeTo).toByteArray()
    }

    private fun verificationUrl(code: String) = "${mail.webBaseUrl}/verify/$code"

    companion object {
        const val TYPE = "reports.finalize"
        private const val MAX_ROUNDS = 48
        private const val PHOTO_YEARS = 5L
        private val ZONE = ZoneId.of("Europe/Berlin")
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
