package de.erika.documents.web

import de.erika.contract.api.DocumentsApi
import de.erika.contract.model.Document
import de.erika.contract.model.DocumentFormat
import de.erika.contract.model.DocumentList
import de.erika.contract.model.DocumentStatus
import de.erika.contract.model.DocumentSummary
import de.erika.contract.model.DocumentType
import de.erika.contract.model.InboxSummary
import de.erika.contract.model.InvoiceSummary
import de.erika.contract.model.MessageSeverity
import de.erika.contract.model.NotEInvoiceReason
import de.erika.contract.model.ProcessingState
import de.erika.contract.model.ReceivedVia
import de.erika.contract.model.ValidationLevel
import de.erika.contract.model.ValidationMessage
import de.erika.contract.model.ValidationResult
import de.erika.documents.DocumentRepository
import de.erika.documents.DocumentRow
import de.erika.documents.InvoiceColumns
import de.erika.documents.StoredResult
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.web.ErikaException
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Read access to documents for the inbox (Stage 5: list, detail, badge counts). Runs in the
 * tenant transaction; another workspace's document is indistinguishable from a missing one (404).
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class DocumentsController(
    private val documents: DocumentRepository,
) : DocumentsApi {
    override fun listDocuments(
        limit: Int,
        cursor: String?,
    ): ResponseEntity<DocumentList> {
        val page = documents.list(limit + 1, cursor?.let(::decodeCursor))
        val items = page.take(limit)
        val next = if (page.size > limit) encodeCursor(items.last().document) else null
        return ResponseEntity.ok(
            DocumentList(
                items =
                    items.map { (doc, invoice, level) ->
                        DocumentSummary(
                            id = doc.id,
                            receivedAt = doc.createdAt,
                            receivedVia = ReceivedVia.forValue(doc.receivedVia),
                            format = DocumentFormat.forValue(doc.format),
                            docType = DocumentType.forValue(doc.docType),
                            status = DocumentStatus.forValue(doc.status),
                            processing = ProcessingState.forValue(doc.processing),
                            fileName = doc.fileName,
                            notEInvoiceReason = doc.notEInvoiceReason?.let(NotEInvoiceReason::forValue),
                            invoiceNumber = invoice?.invoiceNo,
                            issueDate = invoice?.issueDate,
                            sellerName = invoice?.sellerName,
                            grossAmount = invoice?.gross?.money(),
                            currency = invoice?.currency,
                            validationLevel = level?.let(ValidationLevel::forValue),
                            duplicateOf = doc.duplicateOf,
                        )
                    },
                nextCursor = next,
            ),
        )
    }

    override fun getDocument(documentId: UUID): ResponseEntity<Document> {
        val doc = documents.find(documentId) ?: throw ErikaException("DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Document not found.")
        return ResponseEntity.ok(doc.toApi(documents.invoiceColumns(doc.id), documents.latestResult(doc.id)))
    }

    override fun getInboxSummary(): ResponseEntity<InboxSummary> {
        val (newDocuments, processing) = documents.summary()
        return ResponseEntity.ok(InboxSummary(newDocuments = newDocuments, processing = processing))
    }

    private fun DocumentRow.toApi(
        invoice: InvoiceColumns?,
        result: StoredResult?,
    ) = Document(
        id = id,
        direction = Document.Direction.forValue(direction),
        receivedAt = createdAt,
        receivedVia = ReceivedVia.forValue(receivedVia),
        mediaType = mediaType,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        format = DocumentFormat.forValue(format),
        docType = DocumentType.forValue(docType),
        status = DocumentStatus.forValue(status),
        processing = ProcessingState.forValue(processing),
        fileName = fileName,
        profile = formatVersion,
        notEInvoiceReason = notEInvoiceReason?.let(NotEInvoiceReason::forValue),
        duplicateOf = duplicateOf,
        invoice =
            invoice?.let {
                InvoiceSummary(
                    invoiceNumber = it.invoiceNo,
                    issueDate = it.issueDate,
                    dueDate = it.dueDate,
                    currency = it.currency,
                    sellerName = it.sellerName,
                    sellerTaxId = it.sellerTaxId,
                    buyerName = it.buyerName,
                    netAmount = it.net?.money(),
                    taxAmount = it.tax?.money(),
                    grossAmount = it.gross?.money(),
                )
            },
        validation =
            result?.let { r ->
                ValidationResult(
                    level = ValidationLevel.forValue(r.level),
                    applicable = r.applicable,
                    packId = r.packId,
                    checkedAt = r.checkedAt,
                    scenario = r.scenario,
                    messages =
                        r.messages.map { m ->
                            ValidationMessage(
                                ruleId = m.ruleId,
                                severity = MessageSeverity.forValue(m.severity.name),
                                source = ValidationMessage.Source.forValue(m.source.name),
                                text = m.text,
                                originalText = m.originalText,
                                location = m.location,
                            )
                        },
                )
            },
    )

    private fun BigDecimal.money(): String = toPlainString()

    private fun encodeCursor(doc: DocumentRow): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${doc.createdAt}|${doc.id}".toByteArray(StandardCharsets.UTF_8))

    private fun decodeCursor(cursor: String): Pair<OffsetDateTime, UUID> =
        try {
            val (at, id) = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split('|', limit = 2)
            OffsetDateTime.parse(at) to UUID.fromString(id)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            throw ErikaException("INVALID_CURSOR", HttpStatus.BAD_REQUEST, "The cursor is not valid.", e)
        }
}
