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

/**
 * Sends a complete RFC 5322 message exactly as given (e.g. an invoice email whose bytes were
 * archived first, CR-07). Returns the server's last answer, which for most providers contains
 * their message ID ("250 2.0.0 Ok: queued as …"), or null.
 */
fun interface RawMailTransport {
    fun send(
        eml: ByteArray,
        recipients: List<String>,
    ): String?
}

@Component
class SmtpRawMailTransport(
    private val sender: JavaMailSender,
) : RawMailTransport {
    override fun send(
        eml: ByteArray,
        recipients: List<String>,
    ): String? {
        val impl = sender as org.springframework.mail.javamail.JavaMailSenderImpl
        val session = impl.session
        val message = jakarta.mail.internet.MimeMessage(session, eml.inputStream())
        val transport = session.getTransport(impl.protocol ?: "smtp")
        transport.connect(impl.host, impl.port, impl.username, impl.password)
        try {
            // Transport.sendMessage writes the parsed message unchanged (no saveChanges), so
            // what is sent is byte for byte what was archived.
            transport.sendMessage(message, recipients.map { jakarta.mail.internet.InternetAddress(it, true) }.toTypedArray())
            // SMTPTransport (Angus Mail, runtime only) exposes the last answer; looked up reflectively.
            val answer = transport.javaClass.methods.firstOrNull { it.name == "getLastServerResponse" && it.parameterCount == 0 }
            return (answer?.invoke(transport) as String?)?.trim()
        } finally {
            transport.close()
        }
    }
}
