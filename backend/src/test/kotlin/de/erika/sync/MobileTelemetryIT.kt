package de.erika.sync

import de.erika.support.ApiIntegrationTest
import de.erika.support.Phones
import de.erika.support.TestDatabase
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * MARA Stage 9: the app's content-free counters per device and day. A resend replaces the day
 * and adds nothing twice to the counters behind the field-test target (sync success ≥ 99.5 %).
 */
class MobileTelemetryIT : ApiIntegrationTest() {
    private val phones by lazy { Phones(this) }

    @Autowired lateinit var meters: MeterRegistry

    private fun runs(outcome: String) =
        meters
            .find("erika.mara.sync.runs")
            .tag("outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    @Test
    fun `counters per day are replaced on resend and only their growth is counted`() {
        val owner = client()
        val user = accounts.enrolled("telemetry", owner)
        val (phone, _) = phones.signIn(user)
        val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
        val doneBefore = runs("done")
        val failedBefore = runs("failed")

        fun send(vararg days: Pair<LocalDate, Map<String, Int>>) =
            phone.send(
                "PUT",
                "/api/v1/mobile/devices/me/telemetry",
                mapOf("days" to days.map { mapOf("day" to it.first.toString(), "counters" to it.second) }),
            )

        assertEquals(204, send(today to mapOf("syncDone" to 10, "syncFailed" to 1, "outboxMax" to 4)).status)
        assertEquals(204, send(today to mapOf("syncDone" to 10, "syncFailed" to 1, "outboxMax" to 4)).status)
        assertEquals(10.0, runs("done") - doneBefore, "a resend adds nothing")
        assertEquals(1.0, runs("failed") - failedBefore)

        assertEquals(204, send(today to mapOf("syncDone" to 15, "syncFailed" to 1)).status)
        assertEquals(15.0, runs("done") - doneBefore, "only the growth counts")

        // A wrong phone clock: days in the future or older than 180 days are ignored.
        assertEquals(204, send(today.plusDays(5) to mapOf("syncDone" to 99), today.minusDays(400) to mapOf("syncDone" to 99)).status)
        assertEquals(15.0, runs("done") - doneBefore)
        assertEquals(
            1,
            TestDatabase.count("SELECT count(*) FROM mobile_telemetry_day WHERE device_id = ?::uuid", phone.deviceId),
        )
        assertEquals(
            listOf("{\"syncDone\": 15, \"syncFailed\": 1}"),
            TestDatabase.strings("SELECT counters::text FROM mobile_telemetry_day WHERE device_id = ?::uuid", phone.deviceId),
        )

        val negative = send(today to mapOf("syncDone" to -1))
        assertEquals(400, negative.status, "counters can't be negative")
        val days = (0..31).map { mapOf("day" to today.minusDays(it.toLong()).toString(), "counters" to emptyMap<String, Int>()) }
        val tooMany = phone.send("PUT", "/api/v1/mobile/devices/me/telemetry", mapOf("days" to days))
        assertEquals(400, tooMany.status, "at most 31 days per report")
    }

    @Test
    fun `the web session can't send telemetry`() {
        val owner = client()
        accounts.enrolled("telemetry-web", owner)
        val r = owner.put("/api/v1/mobile/devices/me/telemetry", mapOf("days" to emptyList<Any>()))
        assertEquals(401, r.status, r.toString())
    }
}
