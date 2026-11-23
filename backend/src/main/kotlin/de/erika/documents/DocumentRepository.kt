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
    val tags: List<String>,
    val note: String?,
)

/** Filters of the document list (AR-04). Null means "no filter". */
internal data class DocumentFilter(
    val status: String? = null,
    val direction: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val q: String? = null,
    val tag: String? = null,
)

internal data class StatusEventRow(
    val fromStatus: String?,
    val toStatus: String,
    val note: String?,
    val userId: UUID?,
    val at: OffsetDateTime,
)

internal data class DerivedRow(
    val storageKey: String,
    val sha256: String,
    val mediaType: String,
    val fileName: String?,
    val sizeBytes: Long,
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

    fun insertOutgoing(
        id: UUID,
        stored: StoredObject,
        fileName: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO document (id, workspace_id, direction, sha256, file_name, media_type, size_bytes, received_via, status)
                VALUES (:id, :ws, 'OUT', :sha, :fileName, :mediaType, :size, 'CREATED', 'ISSUED')
                """.trimIndent(),
            ).param("id", id)
            .param("ws", stored.workspaceId)
            .param("sha", stored.sha256)
            .param("fileName", fileName)
            .param("mediaType", stored.mediaType)
            .param("size", stored.sizeBytes)
            .update()
    }

    fun statuses(ids: Collection<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .sql("SELECT id, status FROM document WHERE workspace_id = current_workspace_id() AND id IN (:ids)")
            .param("ids", ids.toList())
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("status") }
            .list()
            .toMap()
    }

    fun find(id: UUID): DocumentRow? =
        jdbc
            .sql("$SELECT_DOCUMENT WHERE d.id = :id AND d.workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::mapDocument)
            .optional()
            .orElse(null)

    /**
     * Newest first; keyset pagination on (created_at, id). Search (AR-04): German full text
     * over `parsed_invoice.search_text`, trigram similarity and substring on the supplier name
     * (pg_trgm index), invoice-number prefix, and the file name.
     */
    fun list(
        limit: Int,
        after: Pair<OffsetDateTime, UUID>?,
        filter: DocumentFilter = DocumentFilter(),
    ): List<ListedDocument> {
        val where = mutableListOf("d.workspace_id = current_workspace_id()")
        val params = mutableMapOf<String, Any?>("limit" to limit)
        if (after != null) {
            where += "(d.created_at, d.id) < (:afterAt, :afterId)"
            params["afterAt"] = after.first
            params["afterId"] = after.second
        }
        filter.status?.let {
            where += "d.status = :status"
            params["status"] = it
        }
        filter.direction?.let {
            where += "d.direction = :direction"
            params["direction"] = it
        }
        val day = "coalesce(p.issue_date, CAST(timezone('Europe/Berlin', d.created_at) AS date))"
        filter.from?.let {
            where += "$day >= :from"
            params["from"] = it
        }
        filter.to?.let {
            where += "$day <= :to"
            params["to"] = it
        }
        filter.tag?.let {
            where += ":tag = ANY (d.tags)"
            params["tag"] = it
        }
        filter.q?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where +=
                """
                (p.search @@ websearch_to_tsquery('german', :q)
                 OR p.seller_name ILIKE :like OR p.seller_name % :q
                 OR p.invoice_no ILIKE :prefix
                 OR d.file_name ILIKE :like)
                """.trimIndent()
            params["q"] = it
            params["like"] = "%${escapeLike(it)}%"
            params["prefix"] = "${escapeLike(it)}%"
        }
        var spec =
            jdbc.sql(
                """
                SELECT d.*, p.invoice_no, p.issue_date, p.due_date, p.currency, p.seller_name, p.seller_tax_id, p.buyer_name,
                       p.net, p.tax, p.gross, p.document_id AS parsed_id,
                       (SELECT v.level FROM validation_result v
                         WHERE v.workspace_id = d.workspace_id AND v.document_id = d.id
                         ORDER BY v.checked_at DESC LIMIT 1) AS validation_level
                  FROM document d
                  LEFT JOIN parsed_invoice p ON p.workspace_id = d.workspace_id AND p.document_id = d.id
                 WHERE ${where.joinToString("\n   AND ")}
                 ORDER BY d.created_at DESC, d.id DESC
                 LIMIT :limit
                """.trimIndent(),
            )
        params.forEach { (k, v) -> spec = spec.param(k, v) }
        return spec
            .query { rs, row ->
                ListedDocument(
                    document = mapDocument(rs, row),
                    invoice = if (rs.getObject("parsed_id") != null) mapInvoice(rs) else null,
                    validationLevel = rs.getString("validation_level"),
                )
            }.list()
    }

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
        viewJson: String,
        searchText: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO parsed_invoice (document_id, workspace_id, invoice_no, issue_date, due_date, currency, seller_name,
                                            seller_tax_id, buyer_name, net, tax, gross, data, view, search_text)
                VALUES (:id, :ws, :no, :issue, :due, :currency, :seller, :taxId, :buyer, :net, :tax, :gross, CAST(:data AS jsonb),
                        CAST(:view AS jsonb), :searchText)
                ON CONFLICT (document_id) DO UPDATE
                   SET invoice_no = EXCLUDED.invoice_no, issue_date = EXCLUDED.issue_date, due_date = EXCLUDED.due_date,
                       currency = EXCLUDED.currency, seller_name = EXCLUDED.seller_name, seller_tax_id = EXCLUDED.seller_tax_id,
                       buyer_name = EXCLUDED.buyer_name, net = EXCLUDED.net, tax = EXCLUDED.tax, gross = EXCLUDED.gross,
                       data = EXCLUDED.data, view = EXCLUDED.view, search_text = EXCLUDED.search_text, parsed_at = now()
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
            .param("view", viewJson)
            .param("searchText", searchText)
            .update()
    }

    /** The stored invoice view (JSON), or null if there is none. */
    fun viewJson(documentId: UUID): String? =
        jdbc
            .sql("SELECT view::text FROM parsed_invoice WHERE document_id = :id AND workspace_id = current_workspace_id()")
            .param("id", documentId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    /** Sets the status; returns the previous one, or null if unchanged or not found. */
    fun updateStatus(
        documentId: UUID,
        status: String,
    ): String? =
        jdbc
            .sql(
                """
                UPDATE document d SET status = :status
                  FROM document old
                 WHERE d.id = :id AND d.workspace_id = current_workspace_id()
                   AND old.workspace_id = d.workspace_id AND old.id = d.id AND d.status <> :status
                RETURNING old.status
                """.trimIndent(),
            ).param("status", status)
            .param("id", documentId)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun insertStatusEvent(
        workspaceId: UUID,
        documentId: UUID,
        from: String?,
        to: String,
        note: String?,
        userId: UUID?,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO status_event (id, workspace_id, document_id, from_status, to_status, note, user_id)
                VALUES (:id, :ws, :doc, :from, :to, :note, :user)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("ws", workspaceId)
            .param("doc", documentId)
            .param("from", from)
            .param("to", to)
            .param("note", note)
            .param("user", userId)
            .update()
    }

    fun history(documentId: UUID): List<StatusEventRow> =
        jdbc
            .sql(
                """
                SELECT from_status, to_status, note, user_id, at FROM status_event
                 WHERE document_id = :id AND workspace_id = current_workspace_id() ORDER BY at, id
                """.trimIndent(),
            ).param("id", documentId)
            .query { rs, _ ->
                StatusEventRow(
                    fromStatus = rs.getString("from_status"),
                    toStatus = rs.getString("to_status"),
                    note = rs.getString("note"),
                    userId = rs.getObject("user_id", UUID::class.java),
                    at = rs.getObject("at", OffsetDateTime::class.java),
                )
            }.list()

    fun updateTags(
        documentId: UUID,
        tags: List<String>,
    ) {
        jdbc
            .sql("UPDATE document SET tags = :tags WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("tags", tags.toTypedArray(), Types.ARRAY)
            .param("id", documentId)
            .update()
    }

    fun updateNote(
        documentId: UUID,
        note: String?,
    ) {
        jdbc
            .sql("UPDATE document SET note = :note WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("note", note)
            .param("id", documentId)
            .update()
    }

    fun markPending(documentId: UUID) {
        jdbc
            .sql("UPDATE document SET processing = 'PENDING' WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", documentId)
            .update()
    }

    fun upsertDerived(
        workspaceId: UUID,
        documentId: UUID,
        kind: String,
        idx: Int,
        row: DerivedRow,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO derived_object (workspace_id, document_id, kind, idx, storage_key, sha256, media_type, file_name, size_bytes)
                VALUES (:ws, :doc, :kind, :idx, :key, :sha, :mediaType, :fileName, :size)
                ON CONFLICT (workspace_id, document_id, kind, idx) DO UPDATE
                   SET storage_key = EXCLUDED.storage_key, sha256 = EXCLUDED.sha256, media_type = EXCLUDED.media_type,
                       file_name = EXCLUDED.file_name, size_bytes = EXCLUDED.size_bytes, created_at = now()
                """.trimIndent(),
            ).param("ws", workspaceId)
            .param("doc", documentId)
            .param("kind", kind)
            .param("idx", idx)
            .param("key", row.storageKey)
            .param("sha", row.sha256)
            .param("mediaType", row.mediaType)
            .param("fileName", row.fileName)
            .param("size", row.sizeBytes)
            .update()
    }

    fun derived(
        documentId: UUID,
        kind: String,
        idx: Int,
    ): DerivedRow? =
        jdbc
            .sql(
                """
                SELECT storage_key, sha256, media_type, file_name, size_bytes FROM derived_object
                 WHERE document_id = :doc AND kind = :kind AND idx = :idx AND workspace_id = current_workspace_id()
                """.trimIndent(),
            ).param("doc", documentId)
            .param("kind", kind)
            .param("idx", idx)
            .query { rs, _ ->
                DerivedRow(
                    storageKey = rs.getString("storage_key"),
                    sha256 = rs.getString("sha256"),
                    mediaType = rs.getString("media_type"),
                    fileName = rs.getString("file_name"),
                    sizeBytes = rs.getLong("size_bytes"),
                )
            }.optional()
            .orElse(null)

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
        tags = (rs.getArray("tags")?.array as? Array<*>)?.map { it.toString() }.orEmpty(),
        note = rs.getString("note"),
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

    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val SELECT_DOCUMENT = "SELECT d.* FROM document d"
    }
}
