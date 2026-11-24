package de.erika.intake

import de.erika.platform.export.AccountDataSection
import de.erika.platform.export.jsonRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Sender allow-list and the record of received uploads and emails (EX-04, NF-09). */
@Component
internal class IntakeAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "eingang.json"

    override fun collect(workspaceId: UUID): Any {
        val ws = "WHERE workspace_id = current_workspace_id()"
        return mapOf(
            "settings" to jdbc.jsonRows(json, "SELECT allow_list_enabled, updated_at FROM intake_settings $ws"),
            "allowedSenders" to jdbc.jsonRows(json, "SELECT pattern, created_by, created_at FROM intake_sender $ws ORDER BY pattern"),
            "intakes" to
                jdbc.jsonRows(
                    json,
                    "SELECT id, source, status, reason, file_name, sender, from_address, subject, created_at, released_at FROM intake $ws ORDER BY created_at, id",
                ),
        )
    }
}
