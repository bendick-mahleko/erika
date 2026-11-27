package de.erika.sync

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Content-free counters from the MARA app, per device and day (MARA Stage 9 field test, api/mobile.yaml
 * `putTelemetry`): how syncs and photo uploads ended. A day replaces the stored one, so resends
 * change nothing; the increase is added to the Prometheus counters behind the field-test target
 * "sync success ≥ 99.5 %" (alert `ErikaMaraSyncFailureRate`).
 */
@Service
class MobileTelemetry internal constructor(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
    private val meters: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun save(
        workspaceId: UUID,
        deviceId: UUID,
        days: Map<LocalDate, Map<String, Int>>,
    ) {
        val today = LocalDate.now(clock.withZone(BERLIN))
        for ((day, raw) in days) {
            // A day in the future or long gone is a wrong phone clock: ignored.
            if (day.isAfter(today.plusDays(1)) || day.isBefore(today.minusDays(KEEP_DAYS))) continue
            val counters = raw.filterKeys { it in COUNTERS }.filterValues { it > 0 }
            val before = stored(deviceId, day)
            jdbc
                .sql(
                    """
                    INSERT INTO mobile_telemetry_day (workspace_id, device_id, day, counters) VALUES (:ws, :device, :day, :counters::jsonb)
                    ON CONFLICT (workspace_id, device_id, day) DO UPDATE SET counters = EXCLUDED.counters, updated_at = now()
                    """.trimIndent(),
                ).param("ws", workspaceId)
                .param("device", deviceId)
                .param("day", day)
                .param("counters", json.writeValueAsString(counters))
                .update()
            record(before, counters)
        }
        jdbc
            .sql("DELETE FROM mobile_telemetry_day WHERE workspace_id = current_workspace_id() AND day < :limit")
            .param("limit", today.minusDays(KEEP_DAYS))
            .update()
    }

    private fun stored(
        deviceId: UUID,
        day: LocalDate,
    ): Map<String, Int> =
        jdbc
            .sql(
                "SELECT counters::text FROM mobile_telemetry_day " +
                    "WHERE device_id = :device AND day = :day AND workspace_id = current_workspace_id()",
            ).param("device", deviceId)
            .param("day", day)
            .query(String::class.java)
            .optional()
            .map { text -> json.readTree(text).properties().associate { it.key to it.value.asInt() } }
            .orElse(emptyMap())

    /** Adds what grew since the stored day; a smaller value (the app was reinstalled) counts in full. */
    private fun record(
        before: Map<String, Int>,
        after: Map<String, Int>,
    ) {
        for ((name, value) in after) {
            val (meter, tag, tagValue) = METERS[name] ?: continue
            val old = before[name] ?: 0
            val delta = if (value >= old) value - old else value
            if (delta > 0) meters.counter(meter, tag, tagValue).increment(delta.toDouble())
        }
    }

    companion object {
        private val BERLIN = ZoneId.of("Europe/Berlin")
        const val KEEP_DAYS = 180L

        /** The counters of `TelemetryCounters` in api/mobile.yaml. */
        val COUNTERS =
            setOf(
                "syncDone",
                "syncOffline",
                "syncFailed",
                "pushApplied",
                "pushConflict",
                "pushRejected",
                "uploadDone",
                "uploadOffline",
                "uploadChunkRetries",
                "uploadRestarts",
                "outboxMax",
            )

        private val METERS =
            mapOf(
                "syncDone" to Triple("erika.mara.sync.runs", "outcome", "done"),
                "syncOffline" to Triple("erika.mara.sync.runs", "outcome", "offline"),
                "syncFailed" to Triple("erika.mara.sync.runs", "outcome", "failed"),
                "pushApplied" to Triple("erika.mara.push.results", "status", "applied"),
                "pushConflict" to Triple("erika.mara.push.results", "status", "conflict"),
                "pushRejected" to Triple("erika.mara.push.results", "status", "rejected"),
                "uploadDone" to Triple("erika.mara.upload.runs", "outcome", "done"),
                "uploadOffline" to Triple("erika.mara.upload.runs", "outcome", "offline"),
                "uploadChunkRetries" to Triple("erika.mara.upload.retries", "kind", "chunk"),
                "uploadRestarts" to Triple("erika.mara.upload.retries", "kind", "restart"),
            )
    }
}
