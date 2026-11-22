package de.erika.platform.system

import de.erika.platform.runtime.RuntimeInstance
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

/** Liveness records of background components, read by the health endpoint. */
@Repository
class HeartbeatRepository(
    private val jdbc: JdbcClient,
) {
    fun beat(
        component: String,
        instance: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO system_heartbeat (component, instance, last_seen)
                VALUES (:component, :instance, now())
                ON CONFLICT (component) DO UPDATE SET instance = EXCLUDED.instance, last_seen = EXCLUDED.last_seen
                """.trimIndent(),
            ).param("component", component)
            .param("instance", instance)
            .update()
    }

    fun lastSeen(component: String): Instant? =
        jdbc
            .sql("SELECT last_seen FROM system_heartbeat WHERE component = :component")
            .param("component", component)
            .query(OffsetDateTime::class.java)
            .optional()
            .map { it.toInstant() }
            .orElse(null)

    companion object {
        const val WORKER = "worker"
    }
}

@Component
@Profile(RuntimeInstance.PROFILE_WORKER)
class WorkerHeartbeat(
    private val heartbeats: HeartbeatRepository,
    private val instance: RuntimeInstance,
) {
    @Scheduled(fixedDelayString = "\${erika.worker.heartbeat-interval:30s}")
    fun beat() = heartbeats.beat(HeartbeatRepository.WORKER, instance.id)
}
