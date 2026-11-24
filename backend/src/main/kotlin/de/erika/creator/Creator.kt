package de.erika.creator

import de.erika.documents.DocumentStatus
import de.erika.documents.Documents
import de.erika.identity.WorkspaceProfile
import de.erika.identity.WorkspaceProfiles
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.write.InvoiceWriter
import de.erika.invoicecore.write.OutputFormat
import de.erika.platform.jobs.JobQueue
import de.erika.platform.web.ErikaException
import de.erika.platform.web.Etags
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A draft with what the API shows about it. */
internal data class InvoiceDetails(
    val draft: DraftRow,
    val problems: List<Problem>,
    /** Computed amounts (EN 16931 rounding); null while the draft has no lines. */
    val preview: Invoice?,
    val issued: IssuedRow?,
    val status: DocumentStatus?,
    val original: IssuedRow?,
    val corrections: List<IssuedRow>,
    val deliveries: List<DeliveryRow>,
)

/**
 * The invoice creator (CR-01 … CR-07): drafts, issuing, credit notes and sending. Runs in the
 * request's tenant transaction. Issuing and sending happen in the worker
 * ([IssueInvoiceJobHandler], [SendInvoiceJobHandler]): the API never generates or validates
 * files (ADR 0025, Software Design §7).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class Creator internal constructor(
    private val settingsRepo: InvoiceSettingsRepository,
    private val sequences: NumberSequences,
    private val drafts: DraftRepository,
    private val issuedRepo: IssuedRepository,
    private val deliveries: DeliveryRepository,
    private val profiles: WorkspaceProfiles,
    private val documents: Documents,
    private val jobs: JobQueue,
    private val events: ApplicationEventPublisher,
    private val clock: Clock = Clock.system(BERLIN),
) {
    // --- settings ---------------------------------------------------------------------

    fun settings(workspaceId: UUID): InvoiceSettings = settingsRepo.find(workspaceId)

    fun seller(workspaceId: UUID): WorkspaceProfile = profiles.of(workspaceId)

    /** Settings every invoice needs that are still missing (format-independent). */
    fun missing(
        settings: InvoiceSettings,
        seller: WorkspaceProfile,
    ): List<String> = settings.problems(seller, xrechnung = false).map { it.field.removePrefix("settings.") }

    fun nextNumber(
        workspaceId: UUID,
        settings: InvoiceSettings,
    ): String = sequences.peek(workspaceId, settings.patternFor(InvoiceKind.INVOICE), today())

    internal fun updateSettings(
        workspaceId: UUID,
        actorId: UUID,
        expectedVersion: Int,
        new: InvoiceSettings,
    ): InvoiceSettings {
        listOf("numberPattern" to new.numberPattern, "creditNotePattern" to new.creditNotePattern).forEach { (field, pattern) ->
            if (NumberPattern.parse(pattern) == null) {
                throw ErikaException(
                    "NUMBER_PATTERN_INVALID",
                    HttpStatus.BAD_REQUEST,
                    "The number pattern ($field) needs exactly one counter like {NNNN}; allowed are letters, digits, - _ / . and {YYYY} {YY} {MM}.",
                ).withProperty("field", field)
            }
        }
        val before = settingsRepo.find(workspaceId)
        val saved = settingsRepo.save(workspaceId, expectedVersion, new) ?: throw Etags.conflict()
        val changed = changedFields(before, saved)
        if (changed.isNotEmpty()) events.publishEvent(InvoiceSettingsChanged(workspaceId, actorId, changed))
        return saved
    }

    private fun changedFields(
        a: InvoiceSettings,
        b: InvoiceSettings,
    ) = buildList {
        if (a.contactName != b.contactName || a.contactPhone != b.contactPhone || a.contactEmail != b.contactEmail) add("contact")
        if (a.legalRegistration != b.legalRegistration) add("legalRegistration")
        if (a.iban != b.iban || a.bic != b.bic || a.accountHolder != b.accountHolder) add("bankAccount")
        if (a.kleinunternehmer != b.kleinunternehmer) add("kleinunternehmer")
        if (a.numberPattern != b.numberPattern || a.creditNotePattern != b.creditNotePattern) add("numberPattern")
        if (a.defaultFormat != b.defaultFormat) add("defaultFormat")
        if (a.paymentTermsDays != b.paymentTermsDays) add("paymentTermsDays")
    }

    // --- drafts -----------------------------------------------------------------------

    internal fun createDraft(
        workspaceId: UUID,
        actorId: UUID,
        content: DraftContent,
    ): UUID {
        val settings = settingsRepo.find(workspaceId)
        val id = UUID.randomUUID()
        drafts.insert(id, workspaceId, InvoiceKind.INVOICE, null, content.copy(format = content.format ?: settings.defaultFormat), actorId)
        events.publishEvent(InvoiceDraftCreated(workspaceId, actorId, id, InvoiceKind.INVOICE, null))
        return id
    }

    internal fun details(id: UUID): InvoiceDetails? {
        val draft = drafts.find(id) ?: return null
        val issued = issuedRepo.find(id)
        val settings = settingsRepo.find(draft.workspaceId)
        val seller = profiles.of(draft.workspaceId)
        val original = draft.originalInvoiceId?.let(issuedRepo::find)
        val problems =
            if (draft.state ==
                DraftState.DRAFT
            ) {
                DraftChecks.problems(draft.kind, draft.content, settings, seller)
            } else {
                emptyList()
            }
        val preview =
            if (draft.content.lines.isEmpty()) {
                null
            } else {
                InvoiceWriter.prepare(
                    InvoiceAssembler.build(
                        draft.kind,
                        draft.content,
                        settings,
                        seller,
                        issued?.number,
                        issued?.issueDate ?: draft.content.issueDate ?: today(),
                        original?.let { OriginalReference(it.number, it.issueDate) },
                    ),
                    draft.content.format ?: settings.defaultFormat,
                )
            }
        return InvoiceDetails(
            draft = draft,
            problems = problems,
            preview = preview,
            issued = issued,
            status = issued?.let { documents.statuses(listOf(it.documentId))[it.documentId] },
            original = original,
            corrections = if (issued != null) issuedRepo.correctionsOf(id) else emptyList(),
            deliveries = if (issued != null) deliveries.ofInvoice(id) else emptyList(),
        )
    }

    internal fun updateDraft(
        id: UUID,
        ifMatch: String?,
        content: DraftContent,
    ): DraftRow {
        val draft = drafts.find(id) ?: throw notFound()
        val expectedVersion = Etags.required(ifMatch)
        if (draft.state != DraftState.DRAFT) throw notEditable()
        return drafts.updateContent(id, expectedVersion, content.copy(format = content.format ?: draft.content.format))
            ?: throw Etags.conflict()
    }

    internal fun deleteDraft(
        workspaceId: UUID,
        actorId: UUID,
        id: UUID,
    ) {
        val draft = drafts.find(id) ?: throw notFound()
        if (draft.state != DraftState.DRAFT || !drafts.delete(id)) throw notEditable()
        events.publishEvent(InvoiceDraftDiscarded(workspaceId, actorId, id))
    }

    internal fun list(
        state: DraftState?,
        limit: Int,
        after: Pair<java.time.OffsetDateTime, UUID>?,
    ): Pair<List<ListedInvoice>, Map<UUID, DocumentStatus>> {
        val page = drafts.list(state, limit, after)
        return page to documents.statuses(page.mapNotNull { it.issued?.documentId })
    }

    // --- issuing ----------------------------------------------------------------------

    /**
     * Freezes a complete draft and queues issuing (Flow C). Refused with 422 and the problems
     * while mandatory content is missing (CR-01). Calling it again is harmless.
     */
    internal fun requestIssue(
        workspaceId: UUID,
        actorId: UUID?,
        id: UUID,
    ) {
        val draft = drafts.find(id) ?: throw notFound()
        if (draft.state != DraftState.DRAFT) return
        val problems = DraftChecks.problems(draft.kind, draft.content, settingsRepo.find(workspaceId), profiles.of(workspaceId))
        if (problems.isNotEmpty()) throw incomplete(problems)
        if (!drafts.markIssuing(id)) return
        jobs.enqueue(
            IssueInvoiceJobHandler.TYPE,
            IssueInvoiceJobHandler.Payload(id, actorId),
            workspaceId = workspaceId,
            maxAttempts = IssueInvoiceJobHandler.MAX_ATTEMPTS,
        )
        events.publishEvent(InvoiceIssueRequested(workspaceId, actorId, id))
    }

    /** CR-06: a draft that references an issued invoice, prefilled with its content. */
    internal fun createCorrection(
        workspaceId: UUID,
        actorId: UUID,
        originalId: UUID,
        kind: InvoiceKind,
    ): UUID {
        require(kind != InvoiceKind.INVOICE)
        val original = issuedRepo.find(originalId) ?: throw notFound()
        if (original.kind == InvoiceKind.CREDIT_NOTE || original.kind == InvoiceKind.CANCELLATION) {
            throw ErikaException("CORRECTION_NOT_POSSIBLE", HttpStatus.CONFLICT, "A credit note or cancellation can't itself be corrected.")
        }
        if (kind.cancelsOriginal && documents.statuses(listOf(original.documentId))[original.documentId] == DocumentStatus.CANCELLED) {
            throw ErikaException("ALREADY_CANCELLED", HttpStatus.CONFLICT, "This invoice has already been cancelled or corrected.")
        }
        val source = drafts.find(originalId)!!.content
        val note =
            when (kind) {
                InvoiceKind.CANCELLATION -> "Stornierung der Rechnung ${original.number} vom ${original.issueDate.format(GERMAN_DATE)}."
                InvoiceKind.CREDIT_NOTE -> "Gutschrift zur Rechnung ${original.number} vom ${original.issueDate.format(GERMAN_DATE)}."
                else -> "Diese Rechnung ersetzt die Rechnung ${original.number} vom ${original.issueDate.format(GERMAN_DATE)}."
            }
        val content = source.copy(issueDate = null, dueDate = null, paymentTerms = null, note = note)
        val id = UUID.randomUUID()
        drafts.insert(id, workspaceId, kind, originalId, content, actorId)
        events.publishEvent(InvoiceDraftCreated(workspaceId, actorId, id, kind, originalId))
        return id
    }

    // --- sending ----------------------------------------------------------------------

    /** CR-07: queues the email with the invoice file attached. */
    internal fun requestSend(
        workspaceId: UUID,
        actorId: UUID?,
        invoiceId: UUID,
        to: String,
        subject: String?,
        message: String?,
    ): UUID {
        val issued =
            issuedRepo.find(invoiceId)
                ?: if (drafts.find(invoiceId) != null) throw notIssued() else throw notFound()
        val recipient = to.trim()
        if (!RECIPIENT.matches(recipient)) {
            throw ErikaException("RECIPIENT_INVALID", HttpStatus.BAD_REQUEST, "The recipient is not a valid email address.")
        }
        val id = UUID.randomUUID()
        deliveries.insert(
            id = id,
            workspaceId = workspaceId,
            invoiceId = invoiceId,
            recipient = recipient,
            subject = subject?.trim()?.takeIf { it.isNotEmpty() } ?: defaultSubject(issued),
            message = message?.trim()?.takeIf { it.isNotEmpty() },
            messageId = "<$id@${messageIdDomain()}>",
            requestedBy = actorId,
        )
        jobs.enqueue(
            SendInvoiceJobHandler.TYPE,
            SendInvoiceJobHandler.Payload(id),
            workspaceId = workspaceId,
            maxAttempts = SendInvoiceJobHandler.MAX_ATTEMPTS,
        )
        events.publishEvent(InvoiceSendRequested(workspaceId, actorId, invoiceId, id))
        return id
    }

    internal fun delivery(
        invoiceId: UUID,
        deliveryId: UUID,
    ): DeliveryRow? = deliveries.find(deliveryId)?.takeIf { it.invoiceId == invoiceId }

    internal fun issued(id: UUID): IssuedRow? = issuedRepo.find(id)

    private fun defaultSubject(issued: IssuedRow) =
        when (issued.kind) {
            InvoiceKind.CREDIT_NOTE, InvoiceKind.CANCELLATION -> "Gutschrift ${issued.number}"
            InvoiceKind.CORRECTION -> "Rechnungskorrektur ${issued.number}"
            InvoiceKind.INVOICE -> "Rechnung ${issued.number}"
        }

    private fun messageIdDomain() = "erika.invoice"

    private fun today(): LocalDate = LocalDate.now(clock)

    companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        private val GERMAN_DATE =
            java.time.format.DateTimeFormatter
                .ofPattern("dd.MM.yyyy")
        private val RECIPIENT = Regex("^[^@\\s,;<>]+@[^@\\s,;<>]+\\.[^@\\s,;<>]+$")

        fun notFound() = ErikaException("INVOICE_NOT_FOUND", HttpStatus.NOT_FOUND, "Invoice not found.")

        fun notEditable() =
            ErikaException(
                "INVOICE_NOT_EDITABLE",
                HttpStatus.CONFLICT,
                "Only drafts can be changed. Correct an issued invoice with a credit note or cancellation.",
            )

        fun notIssued() = ErikaException("INVOICE_NOT_ISSUED", HttpStatus.CONFLICT, "Issue the invoice before sending it.")

        fun incomplete(problems: List<Problem>) =
            ErikaException(
                "DRAFT_INCOMPLETE",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "The invoice can't be issued yet: mandatory content is missing or wrong.",
            ).withProperty("problems", problems.map { mapOf("field" to it.field, "code" to it.code) })

        /** Format of the draft as stored, defaulting like the API does. */
        fun formatOf(
            content: DraftContent,
            settings: InvoiceSettings,
        ): OutputFormat = content.format ?: settings.defaultFormat
    }
}

/** Adds a property to the problem details of this exception. */
fun ErikaException.withProperty(
    name: String,
    value: Any,
): ErikaException = apply { body.setProperty(name, value) }
