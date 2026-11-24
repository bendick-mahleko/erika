package de.erika.documents

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import de.erika.archive.Archive
import de.erika.invoicecore.model.Attachment
import de.erika.invoicecore.model.Invoice
import de.erika.invoicecore.view.InvoiceView
import de.erika.invoicecore.view.ViewLanguage
import de.erika.invoicecore.view.ViewRenderer
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.validation.InvoiceValidator
import de.erika.validation.ValidationOutcome
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Reads and validates one received document (Software Design §5, Flow A step 5):
 * detect format → parse → validate with the active pack → write `parsed_invoice` and
 * `validation_result` → business duplicate check → `DocumentValidated`.
 *
 * Idempotent: everything runs in the job's transaction, so a crash leaves no trace and the
 * retry starts over; a validation result is stored at most once per job (`job_id` is unique).
 * After the last attempt the document is marked FAILED instead of staying PENDING forever.
 *
 * The validator exists in the worker only; the API never runs this handler, it only queues it.
 */
@Component
class ParseAndValidateJobHandler internal constructor(
    private val repository: DocumentRepository,
    private val archive: Archive,
    private val validator: ObjectProvider<InvoiceValidator>,
    private val events: ApplicationEventPublisher,
    private val derived: DerivedObjects,
    json: JsonMapper,
    meters: MeterRegistry,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemDefaultZone(),
) : JobHandler<ParseAndValidateJobHandler.Payload> {
    private val log = LoggerFactory.getLogger(javaClass)
    private val failures = meters.counter("erika.documents.processing.failed")

    /** The neutral model without embedded attachment content (that stays in the original). */
    private val modelJson: JsonMapper = json.rebuild().addMixIn(Attachment::class.java, AttachmentWithoutContent::class.java).build()

    /** Savepoint, so a failed last attempt can still record FAILED without half-written results. */
    private val savepoint =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }

    data class Payload(
        val documentId: UUID,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val document = repository.find(payload.documentId)
        if (document == null) {
            log.warn("Document {} not found for job {}; nothing to do", payload.documentId, context.jobId)
            return
        }
        if (repository.hasResultFromJob(context.jobId)) return
        try {
            savepoint.executeWithoutResult { process(document, context) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (context.attempt < MAX_ATTEMPTS) throw e
            failures.increment()
            log.error("Document {} could not be processed after {} attempts; marked FAILED", document.id, context.attempt, e)
            repository.markFailed(document.id)
        }
    }

    private fun process(
        document: DocumentRow,
        context: JobContext,
    ) {
        val content =
            archive.open(document.workspaceId, document.sha256)
                ?: error("Original ${document.sha256.take(12)}… of document ${document.id} is not in the archive")
        val outcome = validator.getObject().check(content.bytes, LocalDate.now(clock))
        val invoice = outcome.parsed?.invoice
        val columns = invoice?.let(::columnsOf)
        if (invoice != null) {
            // VW-01/02/06: the reading view, its PDF copy and the decoded attachments are built
            // here, in the worker, so the API never parses or renders files.
            val view = InvoiceView.of(invoice)
            repository.upsertParsed(
                document.id,
                document.workspaceId,
                columns!!,
                modelJson.writeValueAsString(invoice),
                modelJson.writeValueAsString(view),
                searchTextOf(invoice),
                modelJson.writeValueAsString(InvoiceView.of(invoice, ViewLanguage.EN)),
            )
            derived.storeViewPdf(document.workspaceId, document.id, ViewRenderer.pdf(view), view.title)
            invoice.supportingDocuments.forEachIndexed { i, doc ->
                doc.attachment?.let { derived.storeAttachment(document.workspaceId, document.id, i, it) }
            }
        }
        val duplicateOf = columns?.let { repository.findBusinessDuplicate(document.id, it) }
        val format = formatOf(outcome)
        repository.markProcessed(
            documentId = document.id,
            format = format,
            formatVersion =
                outcome.parsed
                    ?.format
                    ?.profile
                    ?.name,
            notEInvoiceReason = notEInvoiceReasonOf(outcome),
            docType = docTypeOf(invoice),
            duplicateOf = duplicateOf,
        )
        repository.insertResult(document.workspaceId, document.id, context.jobId, outcome.report)
        // Stored at intake with the receipt date; extend if the issue date gives a later end (BR-02).
        archive.retainFor(
            document.workspaceId,
            document.sha256,
            invoice?.issueDate,
            document.createdAt.atZoneSameInstant(BERLIN).toLocalDate(),
        )
        events.publishEvent(
            DocumentValidated(document.workspaceId, document.id, format, outcome.report.level.name, outcome.report.packId, duplicateOf),
        )
    }

    @JsonIgnoreProperties("content")
    private abstract class AttachmentWithoutContent

    companion object {
        const val TYPE = "documents.parse-and-validate"
        const val MAX_ATTEMPTS = 5
        private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

        internal fun formatOf(outcome: ValidationOutcome): String =
            when {
                outcome.parseError != null -> {
                    "UNREADABLE"
                }

                else -> {
                    outcome.parsed!!
                        .format.format.name
                }
            }

        internal fun notEInvoiceReasonOf(outcome: ValidationOutcome): String? {
            val parsed = outcome.parsed ?: return "UNREADABLE"
            return if (parsed.format.isEInvoice) null else parsed.format.reason?.name ?: "UNKNOWN_PROFILE"
        }

        /** Plain PDFs and anything unreadable are OTHER (IN-04); credit notes by type code 381. */
        internal fun docTypeOf(invoice: Invoice?): String =
            when {
                invoice == null -> "OTHER"
                invoice.isCreditNote -> "CREDIT_NOTE"
                else -> "INVOICE"
            }

        internal fun columnsOf(invoice: Invoice) =
            InvoiceColumns(
                invoiceNo = invoice.number?.trim()?.takeIf { it.isNotEmpty() },
                issueDate = invoice.issueDate,
                dueDate = invoice.dueDate,
                currency = invoice.currency?.trim()?.uppercase(),
                sellerName = invoice.seller.name,
                sellerTaxId = (invoice.seller.vatId ?: invoice.seller.taxRegistrationId)?.let(::normaliseTaxId),
                buyerName = invoice.buyer.name,
                net = invoice.totals.totalWithoutVat,
                tax = invoice.totals.totalVat,
                gross = invoice.totals.totalWithVat,
            )

        /** Words for the full-text search (AR-04): parties, numbers, items, notes, references. */
        internal fun searchTextOf(invoice: Invoice): String =
            listOfNotNull(
                invoice.number,
                invoice.seller.name,
                invoice.seller.tradingName,
                invoice.buyer.name,
                invoice.buyerReference,
                invoice.purchaseOrderReference,
                invoice.projectReference,
            ).plus(invoice.lines.mapNotNull { it.item.name })
                .plus(invoice.notes.map { it.text })
                .joinToString(" ")
                .take(MAX_SEARCH_TEXT)

        private const val MAX_SEARCH_TEXT = 100_000

        /** "DE 123 456 789" and "de123456789" are the same tax ID. */
        internal fun normaliseTaxId(value: String): String? =
            value
                .filterNot { it.isWhitespace() || it == '-' || it == '.' || it == '/' }
                .uppercase()
                .takeIf { it.isNotEmpty() }
    }
}
