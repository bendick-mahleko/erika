package de.erika.identity

import de.erika.platform.export.AccountDataSection
import de.erika.platform.export.jsonRows
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Workspace profile, members, invitations and MARA phones for the full account export (EX-04, NF-09). No secrets or tokens. */
@Component
internal class IdentityAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "arbeitsbereich.json"

    override fun collect(workspaceId: UUID): Any =
        mapOf(
            "workspace" to
                jdbc
                    .jsonRows(
                        json,
                        "SELECT id, name, street, postal_code, city, country_code, vat_id, tax_number, plan, created_at, " +
                            "closed_at, delete_after FROM workspace WHERE id = current_workspace_id()",
                    ).firstOrNull(),
            "members" to
                jdbc.jsonRows(
                    json,
                    "SELECT u.id AS user_id, u.display_name, u.email, m.role, m.created_at AS member_since, " +
                        "u.totp_enabled_at IS NOT NULL AS two_factor FROM membership m JOIN app_user u ON u.id = m.user_id " +
                        "WHERE m.workspace_id = current_workspace_id() ORDER BY m.created_at",
                ),
            "invitations" to
                jdbc.jsonRows(
                    json,
                    "SELECT id, email, role, invited_by, created_at, expires_at, accepted_at, accepted_by, revoked_at " +
                        "FROM invitation WHERE workspace_id = current_workspace_id() ORDER BY created_at",
                ),
            "mobileDevices" to
                jdbc.jsonRows(
                    json,
                    "SELECT id, user_id, name, platform, app_version, created_at, last_seen_at, last_sync_at, revoked_at, " +
                        "revoked_by, revoked_reason FROM mobile_device WHERE workspace_id = current_workspace_id() ORDER BY created_at",
                ),
        )

    private fun tools.jackson.databind.JsonNode.firstOrNull() = if (isArray && size() > 0) get(0) else null
}
