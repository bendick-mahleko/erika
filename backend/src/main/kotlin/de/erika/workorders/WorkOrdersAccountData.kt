package de.erika.workorders

import de.erika.platform.export.AccountDataSection
import de.erika.platform.export.jsonRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Sites, staff levels and settings, checklist templates and work orders (MARA, EX-04, M-AC-04). */
@Component
internal class WorkOrdersAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "auftraege.json"

    override fun collect(workspaceId: UUID): Any {
        val ws = "WHERE workspace_id = current_workspace_id()"
        return mapOf(
            "sites" to jdbc.jsonRows(json, "SELECT * FROM site $ws ORDER BY created_at, id"),
            "staffLevels" to jdbc.jsonRows(json, "SELECT * FROM staff_level $ws ORDER BY created_at, id"),
            "staffProfiles" to jdbc.jsonRows(json, "SELECT * FROM staff_profile $ws ORDER BY user_id"),
            "checklistTemplates" to jdbc.jsonRows(json, "SELECT * FROM checklist_template $ws ORDER BY created_at, id"),
            "workOrders" to jdbc.jsonRows(json, "SELECT * FROM work_order $ws ORDER BY created_at, id"),
            "assignments" to
                jdbc.jsonRows(json, "SELECT work_order_id, user_id FROM work_order_assignment $ws ORDER BY work_order_id, user_id"),
        )
    }
}
