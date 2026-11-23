package de.erika.feedback

import de.erika.identity.WorkspaceContacts
import de.erika.platform.mail.Mail
import de.erika.platform.mail.Outbox
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@ConfigurationProperties("erika.feedback")
data class FeedbackProperties(
    /** Team address that receives feedback by email; empty = stored only. */
    val to: String = "",
)

/**
 * Stores feedback and forwards it to the team by email (closed beta, ADR 0030). The text
 * is the user's own message; it is never logged.
 */
@Service
class FeedbackService(
    private val jdbc: JdbcClient,
    private val contacts: WorkspaceContacts,
    private val outbox: Outbox,
    private val props: FeedbackProperties,
    @param:Value("\${erika.version:dev}") private val version: String,
) {
    /** Runs in the request's tenant transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun submit(
        workspaceId: UUID,
        userId: UUID,
        category: String,
        message: String,
        page: String?,
    ): UUID {
        val id = UUID.randomUUID()
        val cleanPage = page?.substringBefore('?')?.substringBefore('#')?.take(300)
        jdbc
            .sql(
                """
                INSERT INTO feedback (id, workspace_id, user_id, category, message, page, app_version)
                VALUES (:id, :ws, :user, :category, :message, :page, :version)
                """.trimIndent(),
            ).param("id", id)
            .param("ws", workspaceId)
            .param("user", userId)
            .param("category", category)
            .param("message", message.trim())
            .param("page", cleanPage)
            .param("version", version)
            .update()

        if (props.to.isNotBlank()) {
            val sender = contacts.member(userId)
            outbox.send(
                Mail(
                    to = props.to,
                    subject = "ERIKA-Feedback ($category) von ${sender?.displayName ?: "unbekannt"}",
                    body =
                        """
                        |Kategorie: $category
                        |Seite: ${cleanPage ?: "–"}
                        |Version: $version
                        |Von: ${sender?.displayName ?: "–"} <${sender?.email ?: "–"}>
                        |Workspace: $workspaceId
                        |Feedback-ID: $id
                        |
                        |${message.trim()}
                        """.trimMargin(),
                ),
            )
        }
        return id
    }
}
