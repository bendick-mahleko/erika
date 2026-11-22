package de.erika.platform.jobs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("erika.jobs")
data class JobProperties(
    /** Maximum number of jobs claimed per poll. */
    val batchSize: Int = 10,
    /** How long a claimed job is reserved for one worker before others may reclaim it. */
    val lease: Duration = Duration.ofMinutes(5),
    /** Pause between polls when the queue is empty. */
    val pollInterval: Duration = Duration.ofSeconds(1),
    /** Turn off to drive the worker manually (tests). */
    val pollingEnabled: Boolean = true,
    /** Default number of attempts before a job is marked FAILED. */
    val defaultMaxAttempts: Int = 5,
)
