package de.erika.platform.system

import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.runtime.RuntimeInstance
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * End-to-end probe of the job pipeline: every API start queues one job, and the
 * worker logs it. Seeing "Startup probe … processed" in the worker log after a
 * deploy proves API → job table → worker works (Build Plan, Stage 1 exit criterion).
 */
data class StartupProbe(
    val apiInstance: String,
    val version: String,
    val startedAt: Instant,
)

@Component
@Profile(RuntimeInstance.PROFILE_API)
class StartupProbeEnqueuer(
    private val jobs: JobQueue,
    private val instance: RuntimeInstance,
    @param:Value("\${erika.version}") private val version: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun enqueue() {
        val id = jobs.enqueue(StartupProbeJobHandler.TYPE, StartupProbe(instance.id, version, Instant.now()), maxAttempts = 3)
        log.info("Queued startup probe job {}", id)
    }
}

@Component
class StartupProbeJobHandler(
    private val heartbeats: HeartbeatRepository,
    private val instance: RuntimeInstance,
) : JobHandler<StartupProbe> {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type = TYPE
    override val payloadType = StartupProbe::class.java

    override fun handle(
        payload: StartupProbe,
        context: JobContext,
    ) {
        heartbeats.beat(HeartbeatRepository.WORKER, instance.id)
        log.info(
            "Startup probe from API instance {} (version {}, started {}) processed by {}",
            payload.apiInstance,
            payload.version,
            payload.startedAt,
            instance.id,
        )
    }

    companion object {
        const val TYPE = "system.startup-probe"
    }
}
