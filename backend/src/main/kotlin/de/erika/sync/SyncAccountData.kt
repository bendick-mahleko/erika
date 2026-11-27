package de.erika.sync

import de.erika.platform.export.AccountDataSection
import de.erika.platform.export.jsonRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The sync module's own data for the full account export (EX-04): the notes on concurrent
 * changes and the app's telemetry counters. The change feed holds only sequence numbers and hashes, so it isn't exported.
 */
@Component
internal class SyncAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "synchronisation.json"

    override fun collect(workspaceId: UUID): Any =
        mapOf(
            "conflicts" to
                jdbc.jsonRows(
                    json,
                    "SELECT id, entity, entity_id, field, server_value, device_value, winner, user_id, device_id, created_at " +
                        "FROM sync_conflict WHERE workspace_id = current_workspace_id() ORDER BY created_at, id",
                ),
            // MARA Stage 9: the app's content-free counters per device and day.
            "telemetry" to
                jdbc.jsonRows(
                    json,
                    "SELECT * FROM mobile_telemetry_day WHERE workspace_id = current_workspace_id() ORDER BY day, device_id",
                ),
        )
}
