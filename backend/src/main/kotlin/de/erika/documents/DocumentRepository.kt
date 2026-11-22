package de.erika.documents

import de.erika.archive.StoredObject
import de.erika.validation.ValidationMessage
import de.erika.validation.ValidationReport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

internal data class DocumentRow(
    val id: UUID,
    val workspaceId: UUID,
    val direction: String,
    val docType: String,
    val format: String,
    val formatVersion: String?,
    val notEInvoiceReason: String?,
    val sha256: String,
    val fileName: String?,
    val mediaType: String,
    val sizeBytes: Long,
    val status: String,
    val processing: String,
    val receivedVia: String,
    val duplicateOf: UUID?,
    val createdAt: OffsetDateTime,
)

/** Indexed columns of `parsed_invoice` (the full model is in `data`). */
internal data class InvoiceColumns(
    val invoiceNo: String?,
    val issueDate: LocalDate?,
    val dueDate: LocalDate?,
    val currency: String?,
    val sellerName: String?,
    val sellerTaxId: String?,
    val buyerName: String?,
    val net: BigDecimal?,
    val tax: BigDecimal?,
    val gross: BigDecimal?,
)

internal data class StoredResult(
    val level: String,
    val applicable: Boolean,
    val packId: String,
    val scenario: String?,
    val checkedAt: OffsetDateTime,
    val messages: List<ValidationMessage>,
)

internal data class ListedDocument(
    val document: DocumentRow,
    val invoice: InvoiceColumns?,
    val validationLevel: String?,
)

/** Tenant tables `document`, `parsed_invoice`, `validation_result`; every query runs under RLS. */
@Repository
internal class DocumentRepository(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) {
    fun findIdBySha256(
        workspaceId: UUID,
        sha256: String,
    ): UUID? =
        jdbc
            .sql("SELECT id FROM document WHERE workspace_id = :ws AND sha256 = :sha")
            .param("ws", workspaceId)
            .param("sha", sha256)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    fun insertIncoming(
        id: UUID,
        stored: StoredObject,
        fileName: String?,
        via: ReceivedVia,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO document (id, workspace_id, direction, sha256, file_name, media_type, size_bytes, received_via)
                VALUES (:id, :ws, 'IN', :sha, :fileName, :mediaType, :size, :via)
                ON CONFLICT (workspace_id, sha256) DO NOTHING
                """.trimIndent(),
            ).param("id", id)
            .param("ws", stored.workspaceId)
            .param("sha", stored.sha256)
            .param("fileName", fileName)
            .param("mediaType", stored.mediaType)
            .param("size", stored.sizeBytes)
            .param("via", via.name)
            .update() == 1

    fun find(id: UUID): DocumentRow? =
        jdbc
            .sql("$SELECT_DOCUMENT WHERE d.id = :id AND d.workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::mapDocument)
            .optional()
            .orElse(null)

    /** Newest first; keyset pagination on (created_at, id). */
    fun list(
        limit: Int,
        after: Pair<OffsetDateTime, UUID>?,
    ): List<ListedDocument> =
        jdbc
            .sql(
                """
                SELECT d.*, p.invoice_no, p.issue_date, p.due_date, p.currency, p.seller_name, p.seller_tax_id, p.buyer_name,
                       p.net, p.tax, p.gross, p.document_id AS parsed_id,
                       (SELECT v.level FROM validation_result v
                         WHERE v.workspace_id = d.workspace_id AND v.document_id = d.id
                         ORDER BY v.checked_at DESC LIMIT 1) AS validation_level
                  FROM document d
                  LEFT JOIN parsed_invoice p ON p.workspace_id = d.workspace_id AND p.document_id = d.id
                 WHERE d.workspace_id = current_workspace_id()
                   AND (CAST(:afterAt AS timestamptz) IS NULL OR (d.created_at, d.id) < (CAST(:afterAt AS timestamptz), :afterId))
                 ORDER BY d.created_at DESC, d.id DESC
                 LIMIT :limit
                """.trimIndent(),
            ).param("afterAt", after?.first, Types.TIMESTAMP_WITH_TIMEZONE)
            .param("afterId", after?.second ?: NO_ID)
            .param("limit", limit)
            .query { rs, row ->
                ListedDocument(
                    document = mapDocument(rs, row),
                    invoice = if (rs.getObject("parsed_id") != null) mapInvoice(rs) else null,
                    validationLevel = rs.getString("validation_level"),
                )
            }.list()

    fun invoiceColumns(documentId: UUID): InvoiceColumns? =
        jdbc
            .sql("SELECT * FROM parsed_invoice WHERE document_id = :id AND workspace_id = current_workspace_id()")
            .param("id", documentId)
            .query { rs, _ -> mapInvoice(rs) }
            .optional()
            .orElse(null)

    fun latestResult(documentId: UUID): StoredResult? =
        jdbc
            .sql(
                """
                SELECT level, applicable, artefact_version, scenario, checked_at, messages::text AS messages
                  FROM validation_result
                 WHERE document_id = :id AND workspace_id = current_workspace_id()
                 ORDER BY checked_at DESC LIMIT 1
                """.trimIndent(),
            ).param("id", documentId)
            .query { rs, _ ->
                StoredResult(
                    level = rs.getString("level"),
                    applicable = rs.getBoolean("applicable"),
                    packId = rs.getString("artefact_version"),
                    scenario = rs.getString("scenario"),
                    checkedAt = rs.getObject("checked_at", OffsetDateTime::class.java),
                    messages = json.readValue(rs.getString("messages"), Array<ValidationMessage>::class.java).toList(),
                )
            }.optional()
            .orElse(null)

    /** (documents with status NEW that are processed, documents still being processed). */
    fun summary(): Pair<Int, Int> =
        jdbc
            .sql(
                """
                SELECT count(*) FILTER (WHERE status = 'NEW' AND processing <> 'PENDING') AS new_documents,
                       count(*) FILTER (WHERE processing = 'PENDING') AS processing
                  FROM document WHERE workspace_id = current_workspace_id() AND direction = 'IN'
                """.trimIndent(),
            ).query { rs, _ -> rs.getInt("new_documents") to rs.getInt("processing") }
            .single()

    fun hasResultFromJob(jobId: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM validation_result WHERE job_id = :job)")
            .param("job", jobId)
            .query(Boolean::class.java)
            .single()

    fun upsertParsed(
        documentId: UUID,
        workspaceId: UUID,
        columns: InvoiceColumns,
        dataJson: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO parsed_invoice (document_id, workspace_id, invoice_no, issue_date, due_date, currency, seller_name,
                                            seller_tax_id, buyer_name, net, tax, gross, data)
                VALUES (:id, :ws, :no, :issue, :due, :currency, :seller, :taxId, :buyer, :net, :tax, :gross, CAST(:data AS jsonb))
                ON CONFLICT (document_id) DO UPDATE
                   SET invoice_no = EXCLUDED.invoice_no, issue_date = EXCLUDED.issue_date, due_date = EXCLUDED.due_date,
                       currency = EXCLUDED.currency, seller_name = EXCLUDED.seller_name, seller_tax_id = EXCLUDED.seller_tax_id,
                       buyer_name = EXCLUDED.buyer_name, net = EXCLUDED.net, tax = EXCLUDED.tax, gross = EXCLUDED.gross,
                       data = EXCLUDED.data, parsed_at = now()
                """.trimIndent(),
            ).param("id", documentId)
            .param("ws", workspaceId)
            .param("no", columns.invoiceNo)
            .param("issue", columns.issueDate)
            .param("due", columns.dueDate)
            .param("currency", columns.currency)
            .param("seller", columns.sellerName)
            .param("taxId", columns.sellerTaxId)
            .param("buyer", columns.buyerName)
            .param("net", columns.net)
            .param("tax", columns.tax)
            .param("gross", columns.gross)
            .param("data", dataJson)
            .update()
    }

    /**
     * Business duplicate (IN-05): the earliest other incoming document with the same supplier
     * tax ID, invoice number, gross amount and currency.
     */
    fun findBusinessDuplicate(
        documentId: UUID,
        columns: InvoiceColumns,
    ): UUID? {
        if (columns.sellerTaxId == null || columns.invoiceNo == null || columns.gross == null) return null
        return jdbc
            .sql(
                """
                SELECT p.document_id FROM parsed_invoice p
                  JOIN document d ON d.workspace_id = p.workspace_id AND d.id = p.document_id
                 WHERE p.workspace_id = current_workspace_id() AND p.document_id <> :id AND d.direction = 'IN'
                   AND p.seller_tax_id = :taxId AND p.invoice_no = :no AND p.gross = :gross
                   AND p.currency IS NOT DISTINCT FROM :currency
                 ORDER BY d.created_at, d.id
                 LIMIT 1
                """.trimIndent(),
            ).param("id", documentId)
            .param("taxId", columns.sellerTaxId)
            .param("no", columns.invoiceNo)
            .param("gross", columns.gross)
            .param("currency", columns.currency)
            .query(UUID::class.java)
            .optional()
            .orElse(null)
    }

    fun markProcessed(
        documentId: UUID,
        format: String,
        formatVersion: String?,
        notEInvoiceReason: String?,
        docType: String,
        duplicateOf: UUID?,
    ) {
        jdbc
            .sql(
                """
                UPDATE document SET format = :format, format_version = :version, not_einvoice_reason = :reason,
                                    doc_type = :docType, duplicate_of = :duplicateOf, processing = 'DONE'
                 WHERE id = :id AND workspace_id = current_workspace_id()
                """.trimIndent(),
            ).param("format", format)
            .param("version", formatVersion)
            .param("reason", notEInvoiceReason)
            .param("docType", docType)
            .param("duplicateOf", duplicateOf)
            .param("id", documentId)
            .update()
    }

    fun markFailed(documentId: UUID) {
        jdbc
            .sql("UPDATE document SET processing = 'FAILED' WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", documentId)
            .update()
    }

    /** Stores one validation run; a second insert by the same job is ignored (idempotent). */
    fun insertResult(
        workspaceId: UUID,
        documentId: UUID,
        jobId: UUID?,
        report: ValidationReport,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO validation_result (id, workspace_id, document_id, job_id, artefact_version, level, applicable,
                                               scenario, messages, duration_ms, checked_at)
                VALUES (:id, :ws, :doc, :job, :pack, :level, :applicable, :scenario, CAST(:messages AS jsonb), :duration, :checkedAt)
                ON CONFLICT (job_id) DO NOTHING
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("ws", workspaceId)
            .param("doc", documentId)
            .param("job", jobId)
            .param("pack", report.packId)
            .param("level", report.level.name)
            .param("applicable", report.applicable)
            .param("scenario", report.scenario)
            .param("messages", json.writeValueAsString(report.messages))
            .param("duration", report.durationMillis)
            .param("checkedAt", OffsetDateTime.ofInstant(report.checkedAt, java.time.ZoneOffset.UTC))
            .update()
    }

    private fun mapDocument(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = DocumentRow(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        direction = rs.getString("direction"),
        docType = rs.getString("doc_type"),
        format = rs.getString("format"),
        formatVersion = rs.getString("format_version"),
        notEInvoiceReason = rs.getString("not_einvoice_reason"),
        sha256 = rs.getString("sha256"),
        fileName = rs.getString("file_name"),
        mediaType = rs.getString("media_type"),
        sizeBytes = rs.getLong("size_bytes"),
        status = rs.getString("status"),
        processing = rs.getString("processing"),
        receivedVia = rs.getString("received_via"),
        duplicateOf = rs.getObject("duplicate_of", UUID::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
    )

    private fun mapInvoice(rs: ResultSet) =
        InvoiceColumns(
            invoiceNo = rs.getString("invoice_no"),
            issueDate = rs.getObject("issue_date", LocalDate::class.java),
            dueDate = rs.getObject("due_date", LocalDate::class.java),
            currency = rs.getString("currency"),
            sellerName = rs.getString("seller_name"),
            sellerTaxId = rs.getString("seller_tax_id"),
            buyerName = rs.getString("buyer_name"),
            net = rs.getBigDecimal("net"),
            tax = rs.getBigDecimal("tax"),
            gross = rs.getBigDecimal("gross"),
        )

    private companion object {
        const val SELECT_DOCUMENT = "SELECT d.* FROM document d"

        /** Placeholder for the keyset parameter on the first page (never compared then). */
        val NO_ID: UUID = UUID(0, 0)
    }
}
