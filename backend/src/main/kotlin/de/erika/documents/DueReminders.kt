package de.erika.documents

import de.erika.identity.WorkspaceContacts
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.mail.Mail
import de.erika.platform.mail.MailProperties
import de.erika.platform.mail.Outbox
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.tenancy.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import java.util.UUID

internal data class DueInvoice(
    val documentId: UUID,
    val sellerName: String?,
    val invoiceNo: String?,
    val gross: BigDecimal?,
    val currency: String?,
)

/**
 * AR-05: an email 3 days before received invoices are due, to members who want it (default on;
 * not tax advisors). One email per member and day lists every invoice due then. Invoices that are
 * paid, booked or disputed are skipped. Idempotent: `due_reminder` records each document and
 * due date, so a re-run (or a retried job) sends nothing twice.
 */
@Component
internal class DueReminderJobHandler(
    private val jdbc: JdbcClient,
    private val tenant: TenantContext,
    private val contacts: WorkspaceContacts,
    private val outbox: Outbox,
    private val messages: MessageSource,
    private val mail: MailProperties,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Berlin")),
) : JobHandler<DueReminderJobHandler.Payload> {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Payload(
        /** The day the reminders are for (today + 3); null = computed when the job runs. */
        val dueDate: LocalDate? = null,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val due = payload.dueDate ?: LocalDate.now(clock).plusDays(DAYS_AHEAD)
        val workspaces =
            jdbc
                .sql("SELECT * FROM all_workspace_ids()")
                .query { rs, _ -> rs.getObject(1, UUID::class.java) }
                .list()
        var sent = 0
        workspaces.forEach { ws ->
            tenant.enterWorkspace(ws)
            val invoices = dueInvoices(due)
            if (invoices.isEmpty()) return@forEach
            contacts.dueDateRecipients().forEach { c ->
                outbox.send(reminder(c.email, c.displayName, c.locale, due, invoices))
                sent++
            }
            invoices.forEach { markReminded(ws, it.documentId, due) }
        }
        if (sent > 0) log.info("Due-date reminders for {}: {} emails", due, sent)
    }

    private fun dueInvoices(due: LocalDate): List<DueInvoice> =
        jdbc
            .sql(
                """
                SELECT d.id, p.seller_name, p.invoice_no, p.gross, p.currency
                  FROM document d JOIN parsed_invoice p ON p.workspace_id = d.workspace_id AND p.document_id = d.id
                 WHERE d.workspace_id = current_workspace_id() AND d.direction = 'IN' AND p.due_date = :due
                   AND d.status NOT IN ('PAID', 'BOOKED', 'DISPUTED')
                   AND NOT EXISTS (SELECT 1 FROM due_reminder r
                                    WHERE r.workspace_id = d.workspace_id AND r.document_id = d.id AND r.due_date = :due)
                 ORDER BY p.seller_name, p.invoice_no
                 LIMIT 200
                """.trimIndent(),
            ).param("due", due)
            .query { rs, _ ->
                DueInvoice(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("seller_name"),
                    rs.getString("invoice_no"),
                    rs.getBigDecimal("gross"),
                    rs.getString("currency"),
                )
            }.list()

    private fun markReminded(
        workspaceId: UUID,
        documentId: UUID,
        due: LocalDate,
    ) {
        jdbc
            .sql("INSERT INTO due_reminder (workspace_id, document_id, due_date) VALUES (:ws, :doc, :due) ON CONFLICT DO NOTHING")
            .param("ws", workspaceId)
            .param("doc", documentId)
            .param("due", due)
            .update()
    }

    private fun reminder(
        to: String,
        name: String,
        localeTag: String,
        due: LocalDate,
        invoices: List<DueInvoice>,
    ): Mail {
        val locale = Locale.forLanguageTag(localeTag)
        val german = locale.language != "en"
        val date = due.format(DateTimeFormatter.ofPattern(if (german) "dd.MM.yyyy" else "yyyy-MM-dd"))
        val lines =
            invoices.joinToString("\n") { i ->
                val amount =
                    i.gross?.let { g ->
                        val f = NumberFormat.getCurrencyInstance(if (german) Locale.GERMANY else Locale.UK)
                        runCatching { f.currency = Currency.getInstance(i.currency ?: "EUR") }
                        f.format(g)
                    }
                "- " + listOfNotNull(i.sellerName, i.invoiceNo, amount).joinToString(", ")
            }
        return Mail(
            to = to,
            subject = messages.getMessage("mail.due.subject", null, locale),
            body = messages.getMessage("mail.due.body", arrayOf(name, date, lines, "${mail.webBaseUrl}/inbox"), locale),
        )
    }

    companion object {
        const val TYPE = "documents.due-reminders"
        const val DAYS_AHEAD = 3L
    }
}

/** Queues the reminders once a day in the morning (worker). */
@Component
@Profile(RuntimeInstance.PROFILE_WORKER)
internal class DueReminderScheduler(
    private val jobs: JobQueue,
) {
    @Scheduled(cron = "\${erika.documents.due-reminder-cron:0 0 7 * * *}", zone = "Europe/Berlin")
    @Transactional
    fun daily() {
        jobs.enqueue(DueReminderJobHandler.TYPE, DueReminderJobHandler.Payload(), maxAttempts = 3)
    }
}
