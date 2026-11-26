package de.erika.sync

import de.erika.identity.MobileDevices
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.UUID

internal data class NotifyDevices(
    val workspaceId: UUID,
)

/** What a push service answered for one token. */
enum class PushOutcome { SENT, TOKEN_INVALID, FAILED }

/**
 * Sends a data-only "sync now" message (`{"type": "sync"}`, no personal data) to one app
 * (ADR 0038). The production sender is FCM, which also reaches iPhones through APNs; until a
 * Firebase project is configured, [LoggingPushSender] only logs.
 */
fun interface PushSender {
    fun sendSyncNow(token: String): PushOutcome
}

internal class LoggingPushSender : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendSyncNow(token: String): PushOutcome {
        log.debug("Push sender not configured; skipped a sync message")
        return PushOutcome.SENT
    }
}

@Configuration(proxyBeanMethods = false)
internal class PushConfiguration {
    @Bean
    @ConditionalOnMissingBean(PushSender::class)
    fun pushSender(): PushSender = LoggingPushSender()
}

/**
 * Worker job queued by [ChangeFeed] once per changing transaction: tells every phone of the
 * workspace with a push token to sync. Idempotent: a repeated "sync now" only makes the phones
 * pull again. Phones that don't get messages still sync on start, in the foreground and when
 * they return to it.
 */
@Component
internal class NotifyDevicesJob(
    private val devices: MobileDevices,
    private val sender: PushSender,
) : JobHandler<NotifyDevices> {
    override val type = TYPE
    override val payloadType = NotifyDevices::class.java

    override fun handle(
        payload: NotifyDevices,
        context: JobContext,
    ) {
        for (target in devices.pushTargets()) {
            if (sender.sendSyncNow(target.token) == PushOutcome.TOKEN_INVALID) devices.clearPushToken(target.deviceId)
        }
    }

    companion object {
        const val TYPE = "sync.notify-devices"
    }
}
