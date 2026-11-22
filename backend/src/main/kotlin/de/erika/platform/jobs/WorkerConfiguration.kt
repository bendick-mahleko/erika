package de.erika.platform.jobs

import de.erika.platform.runtime.RuntimeInstance
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Configuration(proxyBeanMethods = false)
@Profile(RuntimeInstance.PROFILE_WORKER)
@EnableScheduling
class WorkerConfiguration

/** Polls the job table; drains it while there is work, then waits `erika.jobs.poll-interval`. */
@Component
@Profile(RuntimeInstance.PROFILE_WORKER)
@ConditionalOnBooleanProperty(name = ["erika.jobs.polling-enabled"], matchIfMissing = true)
class JobPoller(
    private val worker: JobWorker,
) {
    @Scheduled(fixedDelayString = "\${erika.jobs.poll-interval:1s}")
    fun poll() {
        while (worker.runOnce() > 0) {
            // keep draining
        }
    }
}
