package de.erika.reports

import de.erika.platform.export.AccountDataSection
import de.erika.platform.export.jsonRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Job reports with times, materials, Aufmaß and checklist answers (MARA, EX-04, M-AC-04). */
@Component
internal class ReportsAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "berichte.json"

    override fun collect(workspaceId: UUID): Any {
        val ws = "WHERE workspace_id = current_workspace_id()"
        return mapOf(
            "reports" to jdbc.jsonRows(json, "SELECT * FROM report $ws ORDER BY created_at, id"),
            "timeEntries" to jdbc.jsonRows(json, "SELECT * FROM time_entry $ws ORDER BY started_at, id"),
            "materialLines" to jdbc.jsonRows(json, "SELECT * FROM material_line $ws ORDER BY created_at, id"),
            "aufmassPositions" to jdbc.jsonRows(json, "SELECT * FROM aufmass_position $ws ORDER BY created_at, id"),
            "checklistAnswers" to jdbc.jsonRows(json, "SELECT * FROM checklist_answer $ws ORDER BY report_id, item_key"),
        )
    }
}
