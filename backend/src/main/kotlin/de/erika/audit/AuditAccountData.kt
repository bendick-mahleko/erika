package de.erika.audit

import de.erika.platform.export.AccountDataSection
import de.erika.platform.export.jsonRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The workspace's complete audit log for the full account export (EX-04, NF-10), with the
 * hashes as hex, so the chain can be checked outside ERIKA (docs/ops/audit-chain.md).
 */
@Component
internal class AuditAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "protokoll.json"

    override fun collect(workspaceId: UUID): Any =
        jdbc.jsonRows(
            json,
            "SELECT seq, at, actor, action, payload, encode(prev_hash, 'hex') AS prev_hash, encode(hash, 'hex') AS hash " +
                "FROM audit_log WHERE workspace_id = current_workspace_id() ORDER BY seq",
        )
}
