package de.erika.creator.web

import de.erika.creator.MailEvents
import de.erika.platform.runtime.RuntimeInstance
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

@ConfigurationProperties("erika.creator")
data class CreatorProperties(
    /** Shared secret of the mail provider's delivery webhook (`X-Erika-Webhook-Secret`); empty disables it. */
    val webhookSecret: String = "",
)

data class MailEventRequest(
    val messageId: String? = null,
    val event: String? = null,
    val detail: String? = null,
)

/**
 * `POST /webhooks/mail` (api/webhooks.yaml): delivery results from the outgoing mail provider
 * (CR-07). No session: authenticated by the shared secret, compared in constant time.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class MailWebhookController(
    private val props: CreatorProperties,
    private val events: MailEvents,
) {
    @PostMapping("/webhooks/mail")
    fun receive(
        @RequestHeader(name = "X-Erika-Webhook-Secret", required = false) secret: String?,
        @RequestBody body: MailEventRequest,
    ): ResponseEntity<Unit> {
        if (props.webhookSecret.isBlank() || secret == null ||
            !MessageDigest.isEqual(secret.toByteArray(), props.webhookSecret.toByteArray())
        ) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        val messageId = body.messageId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 300 }
        val event =
            body.event
                ?.trim()
                ?.uppercase()
                ?.takeIf { it in setOf("DELIVERED", "BOUNCED", "FAILED", "COMPLAINED") }
        if (messageId == null || event == null) return ResponseEntity.badRequest().build()
        events.record(messageId, event, body.detail?.take(500))
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }
}
