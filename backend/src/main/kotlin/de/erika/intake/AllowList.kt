package de.erika.intake

import de.erika.platform.audit.AuditableEvent
import de.erika.platform.jobs.JobQueue
import de.erika.platform.web.ErikaException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

internal data class SenderRow(
    val id: UUID,
    val pattern: String,
    val createdAt: OffsetDateTime,
)

internal data class QuarantinedRow(
    val id: UUID,
    val receivedAt: OffsetDateTime,
    val sender: String?,
    val fromAddress: String?,
    val subject: String?,
)

data class AllowListChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val change: String,
    val pattern: String? = null,
) : AuditableEvent {
    override val action = "intake.allow_list.changed"
    override val details get() =
        buildMap {
            put("change", change)
            pattern?.let { put("pattern", it) }
        }
}

/** An email was held back: its sender is not on the allow-list (IN-06). */
data class MailQuarantined(
    override val workspaceId: UUID,
    val intakeId: UUID,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "intake.mail.quarantined"
    override val details get() = mapOf("intakeId" to intakeId.toString())
}

data class QuarantineDecided(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val intakeId: UUID,
    val decision: String,
) : AuditableEvent {
    override val action = "intake.quarantine.decided"
    override val details get() = mapOf("intakeId" to intakeId.toString(), "decision" to decision)
}

/** Matching a sender against the allow-list: exact addresses or whole domains, case-insensitive. */
object SenderPatterns {
    private val ADDRESS = Regex("^[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+$")
    private val DOMAIN = Regex("^@[^@\\s<>]+\\.[^@\\s<>]+$")

    /** The normalised pattern (`name@domain` or `@domain`), or null if it is neither. */
    fun normalise(pattern: String): String? {
        val p = pattern.trim().trim('<', '>').lowercase()
        return p.takeIf { ADDRESS.matches(it) || DOMAIN.matches(it) }
    }

    /** The bare address from `Name <addr@x.de>` or `addr@x.de`, lower case. */
    fun address(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val inner = Regex("<([^>]+)>").find(v)?.groupValues?.get(1) ?: v
        return inner.trim().lowercase().takeIf { ADDRESS.matches(it) }
    }

    fun matches(
        patterns: Collection<String>,
        addresses: Collection<String?>,
    ): Boolean =
        addresses.mapNotNull(::address).any { a ->
            val domain = "@" + a.substringAfter('@')
            patterns.any { it == a || it == domain }
        }
}

@Repository
internal class AllowListRepository(
    private val jdbc: JdbcClient,
) {
    fun enabled(): Boolean =
        jdbc
            .sql("SELECT allow_list_enabled FROM intake_settings WHERE workspace_id = current_workspace_id()")
            .query(Boolean::class.java)
            .optional()
            .orElse(false)

    fun setEnabled(
        workspaceId: UUID,
        enabled: Boolean,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO intake_settings (workspace_id, allow_list_enabled) VALUES (:ws, :on)
                ON CONFLICT (workspace_id) DO UPDATE SET allow_list_enabled = :on, updated_at = now()
                """.trimIndent(),
            ).param("ws", workspaceId)
            .param("on", enabled)
            .update()
    }

    fun senders(): List<SenderRow> =
        jdbc
            .sql("SELECT id, pattern, created_at FROM intake_sender WHERE workspace_id = current_workspace_id() ORDER BY pattern")
            .query {
                rs,
                _,
                ->
                SenderRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("pattern"),
                    rs.getObject("created_at", OffsetDateTime::class.java),
                )
            }.list()

    /** Inserts unless present; returns the ID of the (new or existing) row. */
    fun addSender(
        workspaceId: UUID,
        pattern: String,
        createdBy: UUID?,
    ): UUID {
        jdbc
            .sql(
                """
                INSERT INTO intake_sender (id, workspace_id, pattern, created_by) VALUES (:id, :ws, :p, :by)
                ON CONFLICT (workspace_id, pattern) DO NOTHING
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("ws", workspaceId)
            .param("p", pattern)
            .param("by", createdBy)
            .update()
        return jdbc
            .sql("SELECT id FROM intake_sender WHERE workspace_id = current_workspace_id() AND pattern = :p")
            .param("p", pattern)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) }
            .single()
    }

    fun removeSender(id: UUID): String? =
        jdbc
            .sql("DELETE FROM intake_sender WHERE id = :id AND workspace_id = current_workspace_id() RETURNING pattern")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun quarantined(): List<QuarantinedRow> =
        jdbc
            .sql(
                """
                SELECT id, created_at, sender, from_address, subject FROM intake
                 WHERE workspace_id = current_workspace_id() AND status = 'QUARANTINED'
                 ORDER BY created_at DESC LIMIT 200
                """.trimIndent(),
            ).query { rs, _ ->
                QuarantinedRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("created_at", OffsetDateTime::class.java),
                    rs.getString("sender"),
                    rs.getString("from_address"),
                    rs.getString("subject"),
                )
            }.list()

    fun quarantine(
        id: UUID,
        fromAddress: String?,
        subject: String?,
    ) {
        jdbc
            .sql(
                """
                UPDATE intake SET status = 'QUARANTINED', from_address = :from, subject = :subject, processed_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id() AND status = 'RECEIVED'
                """.trimIndent(),
            ).param("id", id)
            .param("from", fromAddress?.take(MAX_ADDRESS))
            .param("subject", subject?.take(MAX_SUBJECT))
            .update()
    }

    /** QUARANTINED → [to]; false if it isn't quarantined (any more). */
    fun decide(
        id: UUID,
        to: IntakeStatus,
        actorId: UUID,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE intake SET status = :to, released_by = :actor, released_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id() AND status = 'QUARANTINED'
                """.trimIndent(),
            ).param("to", to.name)
            .param("actor", actorId)
            .param("id", id)
            .update() == 1

    private companion object {
        const val MAX_ADDRESS = 320
        const val MAX_SUBJECT = 200
    }
}

/** IN-06: the sender allow-list and the quarantine of emails from other senders. */
@Service
internal class AllowList(
    private val repository: AllowListRepository,
    private val intakes: IntakeRepository,
    private val jobs: JobQueue,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun setEnabled(
        workspaceId: UUID,
        actorId: UUID,
        enabled: Boolean,
    ) {
        if (repository.enabled() == enabled) return
        repository.setEnabled(workspaceId, enabled)
        events.publishEvent(AllowListChanged(workspaceId, actorId, if (enabled) "enabled" else "disabled"))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun addSender(
        workspaceId: UUID,
        actorId: UUID,
        pattern: String,
    ): UUID {
        val clean =
            SenderPatterns.normalise(pattern)
                ?: throw ErikaException(
                    "SENDER_PATTERN_INVALID",
                    HttpStatus.BAD_REQUEST,
                    "Enter an email address or a domain like @lieferant.de.",
                )
        val id = repository.addSender(workspaceId, clean, actorId)
        events.publishEvent(AllowListChanged(workspaceId, actorId, "sender_added", clean))
        return id
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun removeSender(
        workspaceId: UUID,
        actorId: UUID,
        id: UUID,
    ) {
        val pattern = repository.removeSender(id) ?: throw ErikaException("SENDER_NOT_FOUND", HttpStatus.NOT_FOUND, "Sender not found.")
        events.publishEvent(AllowListChanged(workspaceId, actorId, "sender_removed", pattern))
    }

    /** Imports the quarantined email after all; optionally allows its sender from now on. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun release(
        workspaceId: UUID,
        actorId: UUID,
        intakeId: UUID,
        allowSender: Boolean,
    ) {
        val intake = intakes.find(intakeId) ?: throw notFound()
        if (!repository.decide(intakeId, IntakeStatus.RECEIVED, actorId)) throw notFound()
        if (allowSender) {
            (SenderPatterns.address(intake.fromAddress) ?: SenderPatterns.address(intake.sender))?.let {
                addSender(workspaceId, actorId, it)
            }
        }
        jobs.enqueue(
            de.erika.intake.mail.ProcessMailJobHandler.TYPE,
            de.erika.intake.mail.ProcessMailJobHandler
                .Payload(intakeId),
            workspaceId = workspaceId,
        )
        events.publishEvent(QuarantineDecided(workspaceId, actorId, intakeId, "released"))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun discard(
        workspaceId: UUID,
        actorId: UUID,
        intakeId: UUID,
    ) {
        if (!repository.decide(intakeId, IntakeStatus.DISCARDED, actorId)) throw notFound()
        events.publishEvent(QuarantineDecided(workspaceId, actorId, intakeId, "discarded"))
    }

    /** Worker: true if the email must wait in quarantine (allow-list on, not released, sender unknown). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun holdsBack(
        intake: IntakeRow,
        fromHeader: String?,
    ): Boolean {
        if (intake.releasedAt != null || !repository.enabled()) return false
        val patterns = repository.senders().map { it.pattern }
        return !SenderPatterns.matches(patterns, listOf(intake.sender, fromHeader))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun quarantine(
        intake: IntakeRow,
        fromHeader: String?,
        subject: String?,
    ) {
        repository.quarantine(intake.id, fromHeader, subject)
        events.publishEvent(MailQuarantined(intake.workspaceId, intake.id))
    }

    private fun notFound() = ErikaException("QUARANTINED_MAIL_NOT_FOUND", HttpStatus.NOT_FOUND, "No quarantined email with this ID.")
}
