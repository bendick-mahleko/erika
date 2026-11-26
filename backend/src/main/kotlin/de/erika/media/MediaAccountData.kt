package de.erika.media

import de.erika.platform.export.AccountDataSection
import de.erika.platform.export.jsonRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Photo records of job reports (MARA, EX-04, M-AC-04). The image files follow with Stage 8's export. */
@Component
internal class MediaAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "fotos.json"

    override fun collect(workspaceId: UUID): Any =
        mapOf("photos" to jdbc.jsonRows(json, "SELECT * FROM photo WHERE workspace_id = current_workspace_id() ORDER BY taken_at, id"))
}
