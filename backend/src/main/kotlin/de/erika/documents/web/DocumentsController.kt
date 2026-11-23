package de.erika.documents.web

import de.erika.archive.Archive
import de.erika.contract.api.DocumentsApi
import de.erika.contract.model.BulkStatusChange
import de.erika.contract.model.BulkStatusResult
import de.erika.contract.model.Comment
import de.erika.contract.model.CommentList
import de.erika.contract.model.CommentRequest
import de.erika.contract.model.Document
import de.erika.contract.model.DocumentFormat
import de.erika.contract.model.DocumentList
import de.erika.contract.model.DocumentSummary
import de.erika.contract.model.DocumentType
import de.erika.contract.model.DocumentUpdate
import de.erika.contract.model.InboxSummary
import de.erika.contract.model.InvoiceSummary
import de.erika.contract.model.InvoiceView
import de.erika.contract.model.MessageSeverity
import de.erika.contract.model.NotEInvoiceReason
import de.erika.contract.model.ProcessingState
import de.erika.contract.model.ReceivedVia
import de.erika.contract.model.StatusEvent
import de.erika.contract.model.ValidationLevel
import de.erika.contract.model.ValidationMessage
import de.erika.contract.model.ValidationResult
import de.erika.documents.CommentRepository
import de.erika.documents.CommentRow
import de.erika.documents.Comments
import de.erika.documents.DerivedObjects
import de.erika.documents.DocumentFilter
import de.erika.documents.DocumentRepository
import de.erika.documents.DocumentRow
import de.erika.documents.Documents
import de.erika.documents.InvoiceColumns
import de.erika.documents.StoredResult
import de.erika.identity.WorkspaceContacts
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.ErikaException
import de.erika.platform.web.Idempotency
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import de.erika.contract.model.DocumentStatus as ApiStatus
import de.erika.documents.DocumentStatus as Status

/**
 * Documents for the inbox and the viewer (Stage 6). Runs in the tenant transaction; another
 * workspace's document is indistinguishable from a missing one (404). State-changing calls
 * are limited to OWNER and MEMBER by the security configuration (tax advisors only read).
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class DocumentsController(
    private val documents: DocumentRepository,
    private val service: Documents,
    private val derived: DerivedObjects,
    private val archive: Archive,
    private val contacts: WorkspaceContacts,
    private val json: JsonMapper,
    private val comments: Comments,
    private val commentRows: CommentRepository,
    private val idempotency: Idempotency,
) : DocumentsApi {
    override fun listDocuments(
        limit: Int,
        cursor: String?,
        status: ApiStatus?,
        direction: String?,
        from: LocalDate?,
        to: LocalDate?,
        q: String?,
        tag: String?,
    ): ResponseEntity<DocumentList> {
        val filter = DocumentFilter(status?.value, direction, from, to, q?.takeIf { it.isNotBlank() }, tag?.takeIf { it.isNotBlank() })
        val page = documents.list(limit + 1, cursor?.let(::decodeCursor), filter)
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
                            status = ApiStatus.forValue(doc.status),
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
                            tags = doc.tags,
                        )
                    },
                nextCursor = next,
            ),
        )
    }

    override fun getDocument(documentId: UUID): ResponseEntity<Document> = ResponseEntity.ok(load(documentId))

    override fun updateDocument(
        documentId: UUID,
        documentUpdate: DocumentUpdate,
    ): ResponseEntity<Document> {
        val principal = ErikaPrincipal.current()
        val doc = find(documentId)
        documentUpdate.status?.let {
            val status = Status.valueOf(it.value)
            if (status != Status.valueOf(doc.status) && status !in Status.settableBy(doc.direction)) throw statusNotAllowed()
            service.changeStatus(principal.workspaceId, doc.id, status, documentUpdate.statusNote, principal.userId)
        }
        service.updateMetadata(principal.workspaceId, doc.id, documentUpdate.tags, documentUpdate.note, principal.userId)
        return ResponseEntity.ok(load(documentId))
    }

    override fun changeDocumentStatus(bulkStatusChange: BulkStatusChange): ResponseEntity<BulkStatusResult> {
        val principal = ErikaPrincipal.current()
        val status = Status.valueOf(bulkStatusChange.status.value)
        val changed =
            bulkStatusChange.documentIds.distinct().count { id ->
                // Documents whose direction doesn't allow this status are skipped (not counted).
                val doc = documents.find(id) ?: return@count false
                status in Status.settableBy(doc.direction) &&
                    service.changeStatus(principal.workspaceId, id, status, bulkStatusChange.note, principal.userId)
            }
        return ResponseEntity.ok(BulkStatusResult(changed = changed))
    }

    override fun getDocumentView(documentId: UUID): ResponseEntity<InvoiceView> {
        find(documentId)
        val view = documents.viewJson(documentId) ?: throw viewNotAvailable()
        return ResponseEntity.ok(json.readValue(view, InvoiceView::class.java))
    }

    override fun getDocumentViewPdf(documentId: UUID): ResponseEntity<Resource> {
        find(documentId)
        val pdf = derived.read(documentId, DerivedObjects.VIEW_PDF, 0) ?: throw viewNotAvailable()
        return download(pdf.bytes, MediaType.APPLICATION_PDF, pdf.row.fileName ?: "ansichtskopie.pdf", inline = false)
    }

    /** AR-01: the original as archived, hash-checked, with its digest for the recipient. */
    override fun getDocumentOriginal(documentId: UUID): ResponseEntity<Resource> {
        val doc = find(documentId)
        val content = archive.open(doc.workspaceId, doc.sha256) ?: throw notFound()
        val digest = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(content.stored.sha256))
        val type = runCatching { MediaType.parseMediaType(doc.mediaType) }.getOrDefault(MediaType.APPLICATION_OCTET_STREAM)
        return download(content.bytes, type, doc.fileName ?: "original", inline = false) {
            it.set("Repr-Digest", "sha-256=:$digest:")
            it.set("Digest", "sha-256=$digest")
        }
    }

    /** VW-06: decoded attachment, always as a download, never rendered by the browser. */
    override fun getDocumentAttachment(
        documentId: UUID,
        attachmentIndex: Int,
    ): ResponseEntity<Resource> {
        find(documentId)
        val attachment =
            derived.read(documentId, DerivedObjects.ATTACHMENT, attachmentIndex)
                ?: throw ErikaException("ATTACHMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Attachment not found.")
        val type = runCatching { MediaType.parseMediaType(attachment.row.mediaType) }.getOrDefault(MediaType.APPLICATION_OCTET_STREAM)
        return download(attachment.bytes, type, attachment.row.fileName ?: "anhang", inline = false)
    }

    override fun revalidateDocument(documentId: UUID): ResponseEntity<Document> {
        val principal = ErikaPrincipal.current()
        val doc = find(documentId)
        service.revalidate(principal.workspaceId, doc.id, principal.userId)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(load(documentId))
    }

    override fun listDocumentComments(documentId: UUID): ResponseEntity<CommentList> {
        find(documentId)
        val rows = commentRows.forDocument(documentId)
        val names = contacts.displayNames(rows.map { it.authorId }.toSet())
        return ResponseEntity.ok(CommentList(rows.map { it.toApi(names) }))
    }

    /** Every role, tax advisors included (EX-02): a comment changes no business data. */
    override fun addDocumentComment(
        documentId: UUID,
        idempotencyKey: String,
        commentRequest: CommentRequest,
    ): ResponseEntity<Comment> {
        val principal = ErikaPrincipal.current()
        val doc = find(documentId)
        if (commentRequest.body.isBlank()) {
            throw ErikaException("COMMENT_EMPTY", HttpStatus.BAD_REQUEST, "The comment is empty.")
        }
        val result =
            idempotency.once(principal.workspaceId, idempotencyKey, "addDocumentComment") {
                comments.add(principal.workspaceId, doc.id, principal.userId, commentRequest.body)
            }
        val row = commentRows.find(result.resourceId) ?: throw notFound()
        if (row.documentId != doc.id) throw notFound()
        return ResponseEntity.status(HttpStatus.CREATED).body(row.toApi(contacts.displayNames(setOf(row.authorId))))
    }

    private fun CommentRow.toApi(names: Map<UUID, String>) =
        Comment(id = id, body = body, createdAt = createdAt, authorId = authorId, authorName = names[authorId])

    override fun getInboxSummary(): ResponseEntity<InboxSummary> {
        val (newDocuments, processing) = documents.summary()
        return ResponseEntity.ok(InboxSummary(newDocuments = newDocuments, processing = processing))
    }

    // --- helpers --------------------------------------------------------------------

    private fun find(documentId: UUID): DocumentRow = documents.find(documentId) ?: throw notFound()

    private fun notFound() = ErikaException("DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Document not found.")

    private fun statusNotAllowed() =
        ErikaException(
            "STATUS_NOT_ALLOWED",
            HttpStatus.BAD_REQUEST,
            "This status can't be set by hand for this document (outgoing invoices: SENT, PAID, BOOKED).",
        )

    private fun viewNotAvailable() =
        ErikaException("VIEW_NOT_AVAILABLE", HttpStatus.NOT_FOUND, "There is no invoice view for this document (yet).")

    private fun load(documentId: UUID): Document {
        val doc = find(documentId)
        val history = documents.history(doc.id)
        val names = contacts.displayNames(history.mapNotNull { it.userId }.toSet())
        return doc.toApi(documents.invoiceColumns(doc.id), documents.latestResult(doc.id)).copy(
            tags = doc.tags,
            note = doc.note,
            viewAvailable = documents.derived(doc.id, DerivedObjects.VIEW_PDF, 0) != null,
            history =
                history.map {
                    StatusEvent(
                        toStatus = ApiStatus.forValue(it.toStatus),
                        at = it.at,
                        fromStatus = it.fromStatus?.let(ApiStatus::forValue),
                        note = it.note,
                        userId = it.userId,
                        userName = it.userId?.let(names::get),
                    )
                },
        )
    }

    /**
     * A file download: attachment disposition with an RFC 5987 file name, no sniffing, and a
     * Content Security Policy that forbids everything, so even a browser that opens the file
     * can't run scripts from it (Software Design §7).
     */
    private fun download(
        bytes: ByteArray,
        type: MediaType,
        fileName: String,
        inline: Boolean,
        extra: (HttpHeaders) -> Unit = {},
    ): ResponseEntity<Resource> {
        val headers = HttpHeaders()
        headers.contentType = type
        headers.contentDisposition =
            (if (inline) ContentDisposition.inline() else ContentDisposition.attachment())
                .filename(DerivedObjects.safe(fileName).ifBlank { "datei" }, StandardCharsets.UTF_8)
                .build()
        headers.set("X-Content-Type-Options", "nosniff")
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox")
        headers.cacheControl = CacheControl.noStore().cachePrivate().headerValue
        extra(headers)
        return ResponseEntity.ok().headers(headers).body(ByteArrayResource(bytes))
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
        status = ApiStatus.forValue(status),
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
