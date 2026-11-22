package de.erika.identity

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

/**
 * Published API of the `identity` module: who in a workspace wants which notifications.
 * Runs in the caller's transaction, which must be scoped to the workspace.
 */
@Service
class WorkspaceContacts(
    private val jdbc: JdbcClient,
) {
    /** Verified members who asked for an email when new documents arrive. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun newDocumentRecipients(workspaceId: UUID): List<Contact> =
        jdbc
            .sql(
                """
                SELECT u.id, u.email, u.display_name, u.locale
                  FROM membership m JOIN app_user u ON u.id = m.user_id
                 WHERE m.workspace_id = :ws AND m.workspace_id = current_workspace_id()
                   AND m.notify_new_documents AND u.email_verified_at IS NOT NULL
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
}
