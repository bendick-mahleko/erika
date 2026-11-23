package de.erika.export

import de.erika.identity.WorkspaceContacts
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.mail.Mail
import de.erika.platform.mail.MailProperties
import de.erika.platform.mail.Outbox
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.WorkspaceRole
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.storage.StorageProperties
import de.erika.platform.tenancy.TenantContext
import de.erika.platform.web.ErikaException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** Starting exports (API side). */
@Service
internal class Exports(
    private val repository: ExportRepository,
    private val jobs: JobQueue,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Queues an export, in the request's transaction. Every role may export a period (tax
     * advisors included, EX-01); the full account export is for owners (EX-04).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun request(
        workspaceId: UUID,
        actorId: UUID,
        role: WorkspaceRole,
        kind: ExportKind,
        from: LocalDate?,
        to: LocalDate?,
        scope: ExportScope,
    ): UUID {
        if (kind == ExportKind.FULL && role != WorkspaceRole.OWNER) {
            throw ErikaException("FORBIDDEN", HttpStatus.FORBIDDEN, "Only owners can export the whole account.")
        }
        if (kind == ExportKind.PERIOD) {
            if (from == null || to == null) throw invalid("A period export needs `from` and `to`.")
            if (to.isBefore(from)) throw invalid("`to` is before `from`.")
            if (ChronoUnit.DAYS.between(from, to) > MAX_PERIOD_DAYS) throw invalid("A period export covers at most three years.")
        }
        if (repository.queuedCount() >= MAX_QUEUED) {
            throw ErikaException("EXPORTS_PENDING", HttpStatus.CONFLICT, "Several exports are still being built. Please wait for them.")
        }
        val id = UUID.randomUUID()
        val period = kind == ExportKind.PERIOD
        repository.insert(id, workspaceId, kind, from.takeIf { period }, to.takeIf { period }, scope, actorId)
        jobs.enqueue(
            BuildExportJobHandler.TYPE,
            BuildExportJobHandler.Payload(id),
            workspaceId,
            maxAttempts = BuildExportJobHandler.MAX_ATTEMPTS,
        )
        events.publishEvent(ExportRequested(workspaceId, actorId, id, kind, from.takeIf { period }, to.takeIf { period }, scope))
        return id
    }

    private fun invalid(detail: String) = ErikaException("EXPORT_PERIOD_INVALID", HttpStatus.BAD_REQUEST, detail)

    private companion object {
        const val MAX_PERIOD_DAYS = 3L * 366
        const val MAX_QUEUED = 5
    }
}

/** Emails about exports, in the requester's language (texts in `i18n/mail_*.properties`). */
@Component
internal class ExportMails(
    private val messages: MessageSource,
    private val props: MailProperties,
) {
    fun ready(
        to: String,
        name: String,
        localeTag: String,
        export: ExportRow,
        expiresAt: OffsetDateTime,
    ): Mail {
        val locale = Locale.forLanguageTag(localeTag)
        val until =
            expiresAt.atZoneSameInstant(BERLIN).format(
                DateTimeFormatter.ofPattern(
                    if (locale.language ==
                        "de"
                    ) {
                        "dd.MM.yyyy HH:mm"
                    } else {
                        "yyyy-MM-dd HH:mm"
                    },
                ),
            )
        return Mail(
            to = to,
            subject = messages.getMessage("mail.export.subject", null, locale),
            body = messages.getMessage("mail.export.body", arrayOf(name, what(export, locale), link(export), until), locale),
        )
    }

    fun failed(
        to: String,
        name: String,
        localeTag: String,
        export: ExportRow,
    ): Mail {
        val locale = Locale.forLanguageTag(localeTag)
        return Mail(
            to = to,
            subject = messages.getMessage("mail.export.failed.subject", null, locale),
            body = messages.getMessage("mail.export.failed.body", arrayOf(name, what(export, locale), link(export)), locale),
        )
    }

    private fun what(
        export: ExportRow,
        locale: Locale,
    ) = messages.getMessage(
        "mail.export.kind.${export.kind.name}",
        arrayOf<Any>(export.from?.toString().orEmpty(), export.to?.toString().orEmpty()),
        locale,
    )

    /** The page needs a session; the link itself carries no secret. */
    private fun link(export: ExportRow) = "${props.webBaseUrl}/exports?highlight=${export.id}"

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}

/**
 * Builds an export (worker). Idempotent: an export that is no longer QUEUED is left alone, and a
 * retry overwrites the same object. On the last attempt a failure marks the export FAILED (and
 * tells the requester) instead of failing the job again.
 */
@Component
internal class BuildExportJobHandler(
    private val repository: ExportRepository,
    private val builder: ExportBuilder,
    private val storage: ObjectStorage,
    private val storageProps: StorageProperties,
    private val contacts: WorkspaceContacts,
    private val mails: ExportMails,
    private val outbox: Outbox,
    private val events: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) : JobHandler<BuildExportJobHandler.Payload> {
    private val log = LoggerFactory.getLogger(javaClass)
    private val savepoint =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }

    data class Payload(
        val exportId: UUID,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val export = repository.find(payload.exportId) ?: return
        if (export.state != ExportState.QUEUED) return
        val requester = contacts.member(export.requestedBy)
        try {
            val expiresAt = savepoint.execute { build(export) }!!
            requester?.let { outbox.send(mails.ready(it.email, it.displayName, it.locale, export, expiresAt)) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (context.attempt < MAX_ATTEMPTS) throw e
            log.error("Export {} failed after {} attempts", export.id, context.attempt, e)
            repository.markFailed(export.id, e.javaClass.simpleName)
            events.publishEvent(ExportFailed(export.workspaceId, export.id))
            requester?.let { outbox.send(mails.failed(it.email, it.displayName, it.locale, export)) }
        }
    }

    private fun build(export: ExportRow): OffsetDateTime {
        val built = builder.build(export)
        try {
            val key = "exports/${export.workspaceId}/${export.id}.zip"
            storage.putFile(storageProps.exportsBucket, key, built.file, "application/zip")
            val expiresAt = OffsetDateTime.now().plus(VALIDITY)
            repository.markReady(export.id, key, built.fileName, built.sizeBytes, built.sha256, built.documentCount, expiresAt)
            events.publishEvent(ExportCompleted(export.workspaceId, export.id, built.sha256, built.documentCount))
            log.info("Export {} ready: {} documents, {} bytes", export.id, built.documentCount, built.sizeBytes)
            return expiresAt
        } finally {
            Files.deleteIfExists(built.file)
        }
    }

    companion object {
        const val TYPE = "export.build"
        const val MAX_ATTEMPTS = 3

        /** How long the ZIP can be downloaded before it is deleted. */
        val VALIDITY: Duration = Duration.ofDays(7)
    }
}

/** Deletes expired export ZIPs once a day (worker); the bucket's lifecycle rule is the backstop. */
@Component
@Profile(RuntimeInstance.PROFILE_WORKER)
internal class ExportCleanup(
    private val repository: ExportRepository,
    private val storage: ObjectStorage,
    private val storageProps: StorageProperties,
    private val tenant: TenantContext,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    @Scheduled(cron = "\${erika.export.cleanup-cron:0 15 3 * * *}", zone = "Europe/Berlin")
    fun run(): Int {
        val expired = tx.execute { repository.expired() }.orEmpty()
        var deleted = 0
        expired.forEach { (workspaceId, id) ->
            tx.executeWithoutResult {
                tenant.enterWorkspace(workspaceId)
                val export = repository.find(id) ?: return@executeWithoutResult
                export.storageKey?.let { storage.delete(storageProps.exportsBucket, it) }
                if (repository.markExpired(id)) deleted++
            }
        }
        if (deleted > 0) log.info("Deleted {} expired exports", deleted)
        return deleted
    }
}
