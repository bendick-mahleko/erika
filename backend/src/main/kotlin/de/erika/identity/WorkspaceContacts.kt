package de.erika.identity

import de.erika.platform.security.WorkspaceRole
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A member to notify, with the language for the email. */
data class Contact(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val locale: String,
)

/** A person who can be assigned to work orders (MARA): an owner, member or technician. */
data class WorkforceMember(
    val memberId: UUID,
    val userId: UUID,
    val displayName: String,
    val role: WorkspaceRole,
)

/**
 * Published API of the `identity` module: who in a workspace wants which notifications.
 * Runs in the caller's transaction, which must be scoped to the workspace.
 */
@Service
class WorkspaceContacts(
    private val jdbc: JdbcClient,
) {
    /**
     * Display names of the given users, for history entries. Only members of the current
     * workspace are named; others (e.g. removed members) are left out.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun displayNames(userIds: Collection<UUID>): Map<UUID, String> {
        if (userIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT u.id, u.display_name FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE m.workspace_id = current_workspace_id() AND u.id IN (:ids)
                """.trimIndent(),
            ).param("ids", userIds.toList())
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("display_name") }
            .list()
            .toMap()
    }

    /** A member of the current workspace, or null if [userId] is not a member. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun member(userId: UUID): Contact? =
        jdbc
            .sql(
                """
                SELECT u.id, u.email, u.display_name, u.locale
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE m.workspace_id = current_workspace_id() AND u.id = :id
                """.trimIndent(),
            ).param("id", userId)
            .query { rs, _ ->
                Contact(
                    userId = rs.getObject("id", UUID::class.java),
                    email = rs.getString("email"),
                    displayName = rs.getString("display_name"),
                    locale = rs.getString("locale"),
                )
            }.optional()
            .orElse(null)

    /** Verified members who asked for an email when new documents arrive. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun newDocumentRecipients(workspaceId: UUID): List<Contact> =
        jdbc
            .sql(
                """
                SELECT u.id, u.email, u.display_name, u.locale
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE m.workspace_id = :ws AND m.workspace_id = current_workspace_id()
                   AND m.notify_new_documents AND m.role <> 'TECHNICIAN' AND u.email_verified_at IS NOT NULL
                 ORDER BY u.id
                """.trimIndent(),
            ).param("ws", workspaceId)
            .query { rs, _ ->
                Contact(
                    userId = rs.getObject("id", UUID::class.java),
                    email = rs.getString("email"),
                    displayName = rs.getString("display_name"),
                    locale = rs.getString("locale"),
                )
            }.list()

    /** Verified owners of the current workspace (e.g. for billing notices). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun owners(): List<Contact> =
        jdbc
            .sql(
                """
                SELECT u.id, u.email, u.display_name, u.locale
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE m.workspace_id = current_workspace_id() AND m.role = 'OWNER' AND u.email_verified_at IS NOT NULL
                 ORDER BY u.id
                """.trimIndent(),
            ).query { rs, _ ->
                Contact(
                    userId = rs.getObject("id", UUID::class.java),
                    email = rs.getString("email"),
                    displayName = rs.getString("display_name"),
                    locale = rs.getString("locale"),
                )
            }.list()

    /**
     * Owners, members and technicians of the current workspace, by name: the people who can be
     * assigned to work orders (MARA, ADR 0039). Tax advisors are never assigned.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun workforce(): List<WorkforceMember> =
        jdbc
            .sql(
                """
                SELECT m.id, u.id AS user_id, u.display_name, m.role
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE m.workspace_id = current_workspace_id() AND m.role IN ('OWNER', 'MEMBER', 'TECHNICIAN')
                 ORDER BY lower(u.display_name), u.id
                """.trimIndent(),
            ).query { rs, _ ->
                WorkforceMember(
                    memberId = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    displayName = rs.getString("display_name"),
                    role = WorkspaceRole.valueOf(rs.getString("role")),
                )
            }.list()

    /** Verified owners and members (not tax advisors or technicians) who want due-date reminders (AR-05). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun dueDateRecipients(): List<Contact> =
        jdbc
            .sql(
                """
                SELECT u.id, u.email, u.display_name, u.locale
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE m.workspace_id = current_workspace_id() AND m.notify_due_dates AND m.role IN ('OWNER', 'MEMBER')
                   AND u.email_verified_at IS NOT NULL
                 ORDER BY u.id
                """.trimIndent(),
            ).query { rs, _ ->
                Contact(
                    userId = rs.getObject("id", UUID::class.java),
                    email = rs.getString("email"),
                    displayName = rs.getString("display_name"),
                    locale = rs.getString("locale"),
                )
            }.list()
}
