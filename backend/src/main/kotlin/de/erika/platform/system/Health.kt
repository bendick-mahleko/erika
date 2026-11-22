package de.erika.platform.system

import de.erika.contract.api.SystemApi
import de.erika.contract.model.ComponentHealth
import de.erika.contract.model.Health
import de.erika.contract.model.HealthComponents
import de.erika.contract.model.HealthStatus
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Service
class HealthService(
    private val jdbc: JdbcClient,
    private val storage: ObjectStorage,
    private val heartbeats: HeartbeatRepository,
    @param:Value("\${erika.version}") private val version: String,
    @param:Value("\${erika.worker.stale-after:2m}") private val workerStaleAfter: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun check(): Health {
        val database = probe("database") { jdbc.sql("SELECT 1").query(Int::class.java).single() }
        val objectStorage = probe("object storage") { storage.checkAccess() }
        val worker = if (database.status == HealthStatus.UP) workerHealth() else down("Database unavailable")

        val overall =
            when {
                database.status == HealthStatus.DOWN || objectStorage.status == HealthStatus.DOWN -> HealthStatus.DOWN
                worker.status != HealthStatus.UP -> HealthStatus.DEGRADED
                else -> HealthStatus.UP
            }
        return Health(
            status = overall,
            version = version,
            checkedAt = OffsetDateTime.now(ZoneOffset.UTC),
            components = HealthComponents(database = database, objectStorage = objectStorage, worker = worker),
        )
    }

    private fun workerHealth(): ComponentHealth {
        val lastSeen = heartbeats.lastSeen(HeartbeatRepository.WORKER) ?: return degraded("No worker heartbeat yet")
        val age = Duration.between(lastSeen, Instant.now())
        return if (age <= workerStaleAfter) {
            ComponentHealth(status = HealthStatus.UP)
        } else {
            degraded("Last worker heartbeat ${age.toSeconds()} s ago")
        }
    }

    private fun probe(
        name: String,
        check: () -> Unit,
    ): ComponentHealth =
        try {
            check()
            ComponentHealth(status = HealthStatus.UP)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Details go to the log, not to the public response.
            log.warn("Health check for {} failed", name, e)
            down("Unavailable")
        }

    private fun down(detail: String) = ComponentHealth(status = HealthStatus.DOWN, detail = detail)

    private fun degraded(detail: String) = ComponentHealth(status = HealthStatus.DEGRADED, detail = detail)
}

@RestController
@Profile(RuntimeInstance.PROFILE_API)
class HealthController(
    private val health: HealthService,
) : SystemApi {
    override fun getHealth(): ResponseEntity<Health> {
        val result = health.check()
        val status = if (result.status == HealthStatus.DOWN) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.OK
        return ResponseEntity.status(status).body(result)
    }
}
