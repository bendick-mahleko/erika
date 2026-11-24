package de.erika.intake.mail

import de.erika.archive.Archive
import de.erika.documents.ReceivedVia
import de.erika.intake.IntakeAddresses
import de.erika.intake.IntakePipeline
import de.erika.intake.IntakeProperties
import de.erika.intake.IntakeReason
import de.erika.intake.IntakeRepository
import de.erika.intake.IntakeStatus
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.tenancy.TenantContext
import de.erika.platform.web.ErikaException
import jakarta.mail.MessagingException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Stores a received email write-once before anything else (Software Design §5, Flow A
 * step 2), so nothing is lost if a later step fails, and queues its processing.
 */
@Service
internal class MailIntake(
    private val tx: TransactionTemplate,
    private val tenant: TenantContext,
    private val archive: Archive,
    private val repository: IntakeRepository,
    private val jobs: JobQueue,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Berlin")),
) {
    fun receive(
        workspaceId: UUID,
        raw: ByteArray,
        sender: String?,
    ): UUID =
        tx.execute {
            tenant.enterWorkspace(workspaceId)
            val stored = archive.store(workspaceId, raw, "message/rfc822", null, LocalDate.now(clock))
            val id = UUID.randomUUID()
            repository.insertEmail(id, workspaceId, stored.sha256, raw.size.toLong(), sender?.take(MAX_ADDRESS))
            jobs.enqueue(ProcessMailJobHandler.TYPE, ProcessMailJobHandler.Payload(id), workspaceId = workspaceId)
            id
        }!!

    private companion object {
        const val MAX_ADDRESS = 320
    }
}

data class RecipientCheck(
    val recipient: String,
)

/**
 * Endpoints for the inbound mail server (api/internal.yaml, ADR 0024). Outside `/api/v1`,
 * not routed by the reverse proxy, and protected by a shared secret.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
@RequestMapping("/internal/intake")
internal class MailIntakeController(
    private val addresses: IntakeAddresses,
    private val intake: MailIntake,
    private val props: IntakeProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/recipients/check", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun check(
        @RequestHeader(SECRET_HEADER, required = false) secret: String?,
        @RequestBody body: RecipientCheck,
    ): Map<String, Boolean> {
        authorise(secret)
        return mapOf("known" to (addresses.workspaceFor(body.recipient) != null))
    }

    @PostMapping("/mail")
    fun receive(
        @RequestHeader(SECRET_HEADER, required = false) secret: String?,
        @RequestHeader("X-Erika-Recipient") recipient: String,
        @RequestHeader("X-Erika-Sender", required = false) sender: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        authorise(secret)
        val workspaceId =
            addresses.workspaceFor(recipient)
                ?: throw ErikaException("INTAKE_RECIPIENT_UNKNOWN", HttpStatus.NOT_FOUND, "Unknown intake address.")
        if (request.contentLengthLong > props.maxMailBytes) throw tooLarge()
        val raw = readLimited(request.inputStream, props.maxMailBytes)
        val intakeId = intake.receive(workspaceId, raw, sender?.takeIf { it.isNotBlank() })
        log.info("Email received for workspace {} ({} bytes), intake {}", workspaceId, raw.size, intakeId)
        return ResponseEntity.accepted().build()
    }

    private fun authorise(secret: String?) {
        val expected = props.mailSecret
        val ok =
            expected.isNotBlank() && secret != null &&
                MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8))
        if (!ok) throw ErikaException("INTAKE_UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Missing or wrong intake secret.")
    }

    private fun readLimited(
        input: InputStream,
        limit: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw tooLarge()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun tooLarge() = ErikaException("INTAKE_MAIL_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "The email is larger than allowed.")

    private companion object {
        const val SECRET_HEADER = "X-Intake-Secret"
        const val BUFFER = 64 * 1024
    }
}

/**
 * Worker: extracts the attachments of a stored email and imports them like uploads.
 * Idempotent: an intake that is no longer RECEIVED has been processed.
 */
@Component
internal class ProcessMailJobHandler(
    private val repository: IntakeRepository,
    private val archive: Archive,
    private val pipeline: IntakePipeline,
    private val allowList: de.erika.intake.AllowList,
) : JobHandler<ProcessMailJobHandler.Payload> {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Payload(
        val intakeId: UUID,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val intake = repository.find(payload.intakeId) ?: return
        if (intake.status != IntakeStatus.RECEIVED) return
        val raw =
            archive.open(intake.workspaceId, intake.rawSha256!!)?.bytes
                ?: error("Email of intake ${intake.id} is not in the archive")
        // IN-06: with the allow-list on, mail from other senders waits in quarantine (it is archived already).
        val (from, subject) = MailParser.headers(raw)
        if (allowList.holdsBack(intake, from)) {
            allowList.quarantine(intake, from, subject)
            return
        }
        val files =
            try {
                MailParser.attachments(raw)
            } catch (e: MessagingException) {
                log.warn("Intake {}: email could not be parsed ({})", intake.id, e.javaClass.simpleName)
                emptyList()
            } catch (e: IOException) {
                log.warn("Intake {}: email could not be parsed ({})", intake.id, e.javaClass.simpleName)
                emptyList()
            }
        if (files.isEmpty()) {
            pipeline.complete(intake, emptyList(), IntakeStatus.DONE, IntakeReason.NO_ATTACHMENTS)
            return
        }
        pipeline.complete(intake, pipeline.importFiles(intake, files, ReceivedVia.EMAIL), IntakeStatus.DONE, null)
    }

    companion object {
        const val TYPE = "intake.process-mail"
    }
}
