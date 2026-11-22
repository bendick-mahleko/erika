package de.erika.platform.mail

import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/** A plain-text email. Contains no invoice content. */
data class Mail(
    val to: String,
    val subject: String,
    val body: String,
)

@ConfigurationProperties("erika.mail")
data class MailProperties(
    val from: String = "ERIKA <no-reply@erika.local>",
    /** Public base URL of the web app, used in links (e.g. https://app.erika.de). */
    val webBaseUrl: String = "http://localhost:3000",
)

/**
 * Sends mail through the job queue: the mail is queued in the caller's transaction and
 * sent by the worker, with retries. A mail is sent if and only if the change that caused
 * it commits.
 */
@Component
class Outbox(
    private val jobs: JobQueue,
) {
    fun send(mail: Mail) {
        jobs.enqueue(SendMailJobHandler.TYPE, mail, maxAttempts = 8)
    }
}

/** Port for actual delivery; SMTP in every environment (Mailpit locally). */
fun interface MailTransport {
    fun deliver(mail: Mail)
}

@Component
class SmtpMailTransport(
    private val sender: JavaMailSender,
    private val props: MailProperties,
) : MailTransport {
    override fun deliver(mail: Mail) {
        sender.send(
            SimpleMailMessage().apply {
                from = props.from
                setTo(mail.to)
                subject = mail.subject
                text = mail.body
            },
        )
    }
}

/**
 * Idempotency: SMTP has no idempotency key, so a crash between "sent" and "job done"
 * can send a mail twice. For notification mails that is acceptable.
 */
@Component
class SendMailJobHandler(
    private val transport: MailTransport,
    @param:Value("\${erika.mail.enabled:true}") private val enabled: Boolean,
) : JobHandler<Mail> {
    private val log = LoggerFactory.getLogger(javaClass)
    override val type = TYPE
    override val payloadType = Mail::class.java

    override fun handle(
        payload: Mail,
        context: JobContext,
    ) {
        if (!enabled) {
            log.info("Mail sending disabled; dropping mail from job {}", context.jobId)
            return
        }
        transport.deliver(payload)
    }

    companion object {
        const val TYPE = "platform.send-mail"
    }
}
