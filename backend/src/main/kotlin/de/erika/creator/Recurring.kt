package de.erika.creator

import de.erika.identity.WorkspaceContacts
import de.erika.platform.audit.AuditableEvent
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.mail.Mail
import de.erika.platform.mail.MailProperties
import de.erika.platform.mail.Outbox
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.tenancy.TenantContext
import de.erika.platform.web.ErikaException
import de.erika.platform.web.Etags
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class RecurringInterval(
    val months: Long,
) {
    MONTHLY(1),
    QUARTERLY(3),
}

internal data class RecurringRow(
    val id: UUID,
    val workspaceId: UUID,
    val name: String,
    val sourceInvoiceId: UUID,
    val content: DraftContent,
    val interval: RecurringInterval,
    val nextRun: LocalDate,
    val active: Boolean,
    val lastRun: LocalDate?,
    val lastDraftId: UUID?,
    val createdBy: UUID?,
    val version: Int,
) {
    /** The service period of a run on [run]: the month or quarter starting that day. */
    fun periodOf(run: LocalDate): Pair<LocalDate, LocalDate> = run to run.plusMonths(interval.months).minusDays(1)
}

data class RecurringInvoiceChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val recurringId: UUID,
    val change: String,
) : AuditableEvent {
    override val action = "creator.recurring.changed"
    override val details get() = mapOf("recurringId" to recurringId.toString(), "change" to change)
}

/** A draft was created on schedule (CR-09). */
data class RecurringDraftCreated(
    override val workspaceId: UUID,
    val recurringId: UUID,
    val invoiceId: UUID,
    val periodStart: LocalDate,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "creator.recurring.draft_created"
    override val details
        get() = mapOf("recurringId" to recurringId.toString(), "invoiceId" to invoiceId.toString(), "periodStart" to periodStart.toString())
}

@Repository
internal class RecurringRepository(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) {
    fun insert(r: RecurringRow) {
        jdbc
            .sql(
                """
                INSERT INTO recurring_invoice (id, workspace_id, name, source_invoice_id, content, interval, next_run, created_by)
                VALUES (:id, :ws, :name, :source, CAST(:content AS jsonb), :interval, :next, :by)
                """.trimIndent(),
            ).param("id", r.id)
            .param("ws", r.workspaceId)
            .param("name", r.name)
            .param("source", r.sourceInvoiceId)
            .param("content", json.writeValueAsString(r.content))
            .param("interval", r.interval.name)
            .param("next", r.nextRun)
            .param("by", r.createdBy)
            .update()
    }

    fun find(id: UUID): RecurringRow? =
        jdbc
            .sql("SELECT * FROM recurring_invoice WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun list(): List<RecurringRow> =
        jdbc
            .sql("SELECT * FROM recurring_invoice WHERE workspace_id = current_workspace_id() ORDER BY active DESC, next_run, name")
            .query(::map)
            .list()

    /** Active templates due on or before [day], locked for the run. */
    fun dueForUpdate(day: LocalDate): List<RecurringRow> =
        jdbc
            .sql(
                """
                SELECT * FROM recurring_invoice WHERE workspace_id = current_workspace_id() AND active AND next_run <= :day
                 ORDER BY next_run FOR UPDATE SKIP LOCKED
                """.trimIndent(),
            ).param("day", day)
            .query(::map)
            .list()

    fun update(
        id: UUID,
        expectedVersion: Int,
        name: String,
        interval: RecurringInterval,
        nextRun: LocalDate,
        active: Boolean,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE recurring_invoice SET name = :name, interval = :interval, next_run = :next, active = :active,
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id() AND version = :expected
                """.trimIndent(),
            ).param("name", name)
            .param("interval", interval.name)
            .param("next", nextRun)
            .param("active", active)
            .param("id", id)
            .param("expected", expectedVersion)
            .update() == 1

    fun recordRun(
        id: UUID,
        ran: LocalDate,
        next: LocalDate,
        draftId: UUID,
    ) {
        jdbc
            .sql(
                """
                UPDATE recurring_invoice SET last_run = :ran, next_run = :next, last_draft_id = :draft, updated_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id()
                """.trimIndent(),
            ).param("ran", ran)
            .param("next", next)
            .param("draft", draftId)
            .param("id", id)
            .update()
    }

    fun delete(id: UUID): Boolean =
        jdbc
            .sql("DELETE FROM recurring_invoice WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .update() == 1

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = RecurringRow(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        name = rs.getString("name"),
        sourceInvoiceId = rs.getObject("source_invoice_id", UUID::class.java),
        content = json.readValue(rs.getString("content"), DraftContent::class.java),
        interval = RecurringInterval.valueOf(rs.getString("interval")),
        nextRun = rs.getObject("next_run", LocalDate::class.java),
        active = rs.getBoolean("active"),
        lastRun = rs.getObject("last_run", LocalDate::class.java),
        lastDraftId = rs.getObject("last_draft_id", UUID::class.java),
        createdBy = rs.getObject("created_by", UUID::class.java),
        version = rs.getInt("version"),
    )
}

/**
 * Recurring invoices (CR-09): a template copied from a draft or invoice; on each run date a new
 * draft with the run's service period is created. Drafts are never issued automatically: a
 * person checks and issues them.
 */
@Service
internal class RecurringInvoices(
    private val repository: RecurringRepository,
    private val drafts: DraftRepository,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun create(
        workspaceId: UUID,
        actorId: UUID,
        sourceInvoiceId: UUID,
        name: String,
        interval: RecurringInterval,
        nextRun: LocalDate,
    ): UUID {
        val source = drafts.find(sourceInvoiceId) ?: throw Creator.notFound()
        if (source.kind != InvoiceKind.INVOICE) {
            throw ErikaException("RECURRING_NOT_POSSIBLE", HttpStatus.CONFLICT, "Only invoices can recur, not credit notes or corrections.")
        }
        val id = UUID.randomUUID()
        val content = source.content.copy(issueDate = null, deliveryDate = null, dueDate = null, periodStart = null, periodEnd = null)
        repository.insert(
            RecurringRow(id, workspaceId, name.trim(), sourceInvoiceId, content, interval, nextRun, true, null, null, actorId, 0),
        )
        events.publishEvent(RecurringInvoiceChanged(workspaceId, actorId, id, "created"))
        return id
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun update(
        workspaceId: UUID,
        actorId: UUID,
        id: UUID,
        ifMatch: String?,
        name: String,
        interval: RecurringInterval,
        nextRun: LocalDate,
        active: Boolean,
    ) {
        repository.find(id) ?: throw notFound()
        val expected = Etags.required(ifMatch)
        if (!repository.update(id, expected, name.trim(), interval, nextRun, active)) throw Etags.conflict()
        events.publishEvent(RecurringInvoiceChanged(workspaceId, actorId, id, if (active) "updated" else "paused"))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun delete(
        workspaceId: UUID,
        actorId: UUID,
        id: UUID,
    ) {
        if (!repository.delete(id)) throw notFound()
        events.publishEvent(RecurringInvoiceChanged(workspaceId, actorId, id, "deleted"))
    }

    /**
     * Creates the drafts due on or before [today] in the current workspace (also catching up
     * missed runs, at most [MAX_CATCH_UP] per template). Returns the created drafts with their templates.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun runDue(today: LocalDate): List<Pair<RecurringRow, Pair<UUID, LocalDate>>> =
        repository.dueForUpdate(today).flatMap { template ->
            var next = template.nextRun
            val created = mutableListOf<Pair<RecurringRow, Pair<UUID, LocalDate>>>()
            while (!next.isAfter(today) && created.size < MAX_CATCH_UP) {
                val (start, end) = template.periodOf(next)
                val draftId = UUID.randomUUID()
                drafts.insert(
                    draftId,
                    template.workspaceId,
                    InvoiceKind.INVOICE,
                    null,
                    template.content.copy(periodStart = start, periodEnd = end),
                    template.createdBy,
                )
                events.publishEvent(InvoiceDraftCreated(template.workspaceId, null, draftId, InvoiceKind.INVOICE, null))
                events.publishEvent(RecurringDraftCreated(template.workspaceId, template.id, draftId, start))
                repository.recordRun(template.id, next, next.plusMonths(template.interval.months), draftId)
                created += template to (draftId to start)
                next = next.plusMonths(template.interval.months)
            }
            created
        }

    private fun notFound() = ErikaException("RECURRING_NOT_FOUND", HttpStatus.NOT_FOUND, "Recurring invoice not found.")

    private companion object {
        const val MAX_CATCH_UP = 12
    }
}

/** Worker: creates the due drafts in every workspace and tells whoever set the template up. */
@Component
internal class RecurringInvoicesJobHandler(
    private val jdbc: JdbcClient,
    private val tenant: TenantContext,
    private val recurring: RecurringInvoices,
    private val contacts: WorkspaceContacts,
    private val outbox: Outbox,
    private val messages: MessageSource,
    private val mail: MailProperties,
    private val clock: Clock = Clock.system(Creator.BERLIN),
) : JobHandler<RecurringInvoicesJobHandler.Payload> {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Payload(
        val day: LocalDate? = null,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val today = payload.day ?: LocalDate.now(clock)
        val workspaces = jdbc.sql("SELECT * FROM all_workspace_ids()").query { rs, _ -> rs.getObject(1, UUID::class.java) }.list()
        var count = 0
        workspaces.forEach { ws ->
            tenant.enterWorkspace(ws)
            recurring.runDue(today).forEach { (template, created) ->
                count++
                val (draftId, start) = created
                val contact = template.createdBy?.let(contacts::member) ?: return@forEach
                val locale = Locale.forLanguageTag(contact.locale)
                val pattern = DateTimeFormatter.ofPattern(if (locale.language == "en") "yyyy-MM-dd" else "dd.MM.yyyy")
                val (from, to) = template.periodOf(start)
                outbox.send(
                    Mail(
                        to = contact.email,
                        subject = messages.getMessage("mail.recurring.subject", arrayOf(template.name), locale),
                        body =
                            messages.getMessage(
                                "mail.recurring.body",
                                arrayOf(
                                    template.name,
                                    contact.displayName,
                                    "${from.format(pattern)} – ${to.format(pattern)}",
                                    "${mail.webBaseUrl}/invoices/$draftId",
                                ),
                                locale,
                            ),
                    ),
                )
            }
        }
        if (count > 0) log.info("Recurring invoices: {} drafts created for {}", count, today)
    }

    companion object {
        const val TYPE = "creator.recurring-invoices"
    }
}

@Component
@Profile(RuntimeInstance.PROFILE_WORKER)
internal class RecurringInvoicesScheduler(
    private val jobs: JobQueue,
) {
    @Scheduled(cron = "\${erika.creator.recurring-cron:0 30 5 * * *}", zone = "Europe/Berlin")
    @Transactional
    fun daily() {
        jobs.enqueue(RecurringInvoicesJobHandler.TYPE, RecurringInvoicesJobHandler.Payload(), maxAttempts = 3)
    }
}
