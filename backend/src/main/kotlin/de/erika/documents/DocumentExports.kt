package de.erika.documents

import de.erika.platform.export.AccountDataSection
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** One VAT breakdown line of an invoice (BG-23). */
data class ExportVatLine(
    val categoryCode: String?,
    val rate: BigDecimal?,
    val taxableAmount: BigDecimal?,
    val taxAmount: BigDecimal?,
)

/** A document as the period export needs it (published API of `documents`, EX-01). */
data class ExportDocument(
    val id: UUID,
    /** IN (received) or OUT (issued). */
    val direction: String,
    val sha256: String,
    val fileName: String?,
    val mediaType: String,
    val receivedAt: OffsetDateTime,
    val format: String,
    val status: String,
    val validationLevel: String?,
    val invoiceNumber: String?,
    val issueDate: LocalDate?,
    val dueDate: LocalDate?,
    /** BT-3, e.g. 380 invoice, 381 credit note. */
    val typeCode: String?,
    val currency: String?,
    val sellerName: String?,
    val sellerVatId: String?,
    val buyerName: String?,
    val buyerVatId: String?,
    val net: BigDecimal?,
    val tax: BigDecimal?,
    val gross: BigDecimal?,
    val vatLines: List<ExportVatLine>,
) {
    /** The date the period filter uses: invoice date, else the day of receipt (Berlin time). */
    val bookingDate: LocalDate
        get() = issueDate ?: receivedAt.atZoneSameInstant(java.time.ZoneId.of("Europe/Berlin")).toLocalDate()
}

/**
 * Published API of `documents` for exports: the documents of a period with their invoice data,
 * read in the caller's (tenant-scoped) transaction.
 */
@Service
class DocumentExports internal constructor(
    private val jdbc: JdbcClient,
) {
    /**
     * Documents whose invoice date (or receipt date, when there is none) lies in [from]…[to],
     * oldest first; [direction] IN, OUT or null for both.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun forPeriod(
        from: LocalDate,
        to: LocalDate,
        direction: String?,
    ): List<ExportDocument> {
        val day = "coalesce(p.issue_date, CAST(timezone('Europe/Berlin', d.created_at) AS date))"
        val rows =
            jdbc
                .sql(
                    """
                    SELECT d.id, d.direction, d.sha256, d.file_name, d.media_type, d.created_at, d.format, d.status,
                           p.invoice_no, p.issue_date, p.due_date, p.currency, p.seller_name, p.buyer_name, p.net, p.tax, p.gross,
                           p.data->>'typeCode' AS type_code, p.data->'seller'->>'vatId' AS seller_vat_id,
                           p.data->'buyer'->>'vatId' AS buyer_vat_id,
                           (SELECT v.level FROM validation_result v
                             WHERE v.workspace_id = d.workspace_id AND v.document_id = d.id
                             ORDER BY v.checked_at DESC LIMIT 1) AS validation_level
                      FROM document d
                      LEFT JOIN parsed_invoice p ON p.workspace_id = d.workspace_id AND p.document_id = d.id
                     WHERE d.workspace_id = current_workspace_id()
                       AND $day BETWEEN :from AND :to
                       AND (CAST(:direction AS text) IS NULL OR d.direction = :direction)
                     ORDER BY $day, d.created_at, d.id
                    """.trimIndent(),
                ).param("from", from)
                .param("to", to)
                .param("direction", direction)
                .query(::map)
                .list()
        if (rows.isEmpty()) return rows
        val lines = vatLines(rows.map { it.id })
        return rows.map { it.copy(vatLines = lines[it.id].orEmpty()) }
    }

    private fun vatLines(ids: List<UUID>): Map<UUID, List<ExportVatLine>> =
        ids
            .chunked(CHUNK)
            .flatMap { chunk ->
                jdbc
                    .sql(
                        """
                        SELECT p.document_id, v->>'categoryCode' AS category, (v->>'rate')::numeric AS rate,
                               (v->>'taxableAmount')::numeric AS taxable, (v->>'taxAmount')::numeric AS tax
                          FROM parsed_invoice p, jsonb_array_elements(coalesce(p.data->'vatBreakdown', '[]'::jsonb)) WITH ORDINALITY AS t(v, n)
                         WHERE p.workspace_id = current_workspace_id() AND p.document_id IN (:ids)
                         ORDER BY p.document_id, n
                        """.trimIndent(),
                    ).param("ids", chunk)
                    .query { rs, _ ->
                        rs.getObject("document_id", UUID::class.java) to
                            ExportVatLine(
                                rs.getString("category"),
                                rs.getBigDecimal("rate"),
                                rs.getBigDecimal("taxable"),
                                rs.getBigDecimal("tax"),
                            )
                    }.list()
            }.groupBy({ it.first }, { it.second })

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = ExportDocument(
        id = rs.getObject("id", UUID::class.java),
        direction = rs.getString("direction"),
        sha256 = rs.getString("sha256"),
        fileName = rs.getString("file_name"),
        mediaType = rs.getString("media_type"),
        receivedAt = rs.getObject("created_at", OffsetDateTime::class.java),
        format = rs.getString("format"),
        status = rs.getString("status"),
        validationLevel = rs.getString("validation_level"),
        invoiceNumber = rs.getString("invoice_no"),
        issueDate = rs.getObject("issue_date", LocalDate::class.java),
        dueDate = rs.getObject("due_date", LocalDate::class.java),
        typeCode = rs.getString("type_code"),
        currency = rs.getString("currency"),
        sellerName = rs.getString("seller_name"),
        sellerVatId = rs.getString("seller_vat_id"),
        buyerName = rs.getString("buyer_name"),
        buyerVatId = rs.getString("buyer_vat_id"),
        net = rs.getBigDecimal("net"),
        tax = rs.getBigDecimal("tax"),
        gross = rs.getBigDecimal("gross"),
        vatLines = emptyList(),
    )

    private companion object {
        const val CHUNK = 500
    }
}

/**
 * The documents part of the full account export (EX-04, NF-09): every document with its
 * metadata, invoice data, validation results, status history and comments. The originals
 * themselves are added by the export from the archive.
 */
@Component
internal class DocumentsAccountData(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccountDataSection {
    override val fileName = "dokumente.json"

    override fun collect(workspaceId: UUID): Any {
        fun <T> byDocument(
            sql: String,
            mapper: (ResultSet) -> T,
        ): Map<UUID, List<T>> =
            jdbc
                .sql(sql)
                .query { rs, _ -> rs.getObject("document_id", UUID::class.java) to mapper(rs) }
                .list()
                .groupBy({ it.first }, { it.second })

        val history =
            byDocument(
                "SELECT document_id, from_status, to_status, note, user_id, at FROM status_event " +
                    "WHERE workspace_id = current_workspace_id() ORDER BY at, id",
            ) { rs ->
                mapOf(
                    "from" to rs.getString("from_status"),
                    "to" to rs.getString("to_status"),
                    "note" to rs.getString("note"),
                    "userId" to rs.getString("user_id"),
                    "at" to rs.getObject("at", OffsetDateTime::class.java).toString(),
                )
            }
        val comments =
            byDocument(
                "SELECT document_id, author_id, body, created_at FROM document_comment " +
                    "WHERE workspace_id = current_workspace_id() ORDER BY created_at, id",
            ) { rs ->
                mapOf(
                    "authorId" to rs.getString("author_id"),
                    "body" to rs.getString("body"),
                    "at" to rs.getObject("created_at", OffsetDateTime::class.java).toString(),
                )
            }
        val validations =
            byDocument(
                "SELECT document_id, artefact_version, level, applicable, scenario, messages::text AS messages, checked_at " +
                    "FROM validation_result WHERE workspace_id = current_workspace_id() ORDER BY checked_at, id",
            ) { rs ->
                mapOf(
                    "packId" to rs.getString("artefact_version"),
                    "level" to rs.getString("level"),
                    "applicable" to rs.getBoolean("applicable"),
                    "scenario" to rs.getString("scenario"),
                    "messages" to json.readTree(rs.getString("messages")),
                    "checkedAt" to rs.getObject("checked_at", OffsetDateTime::class.java).toString(),
                )
            }
        return jdbc
            .sql(
                """
                SELECT d.*, p.data::text AS invoice_data
                  FROM document d LEFT JOIN parsed_invoice p ON p.workspace_id = d.workspace_id AND p.document_id = d.id
                 WHERE d.workspace_id = current_workspace_id()
                 ORDER BY d.created_at, d.id
                """.trimIndent(),
            ).query { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                linkedMapOf<String, Any?>(
                    "id" to id.toString(),
                    "direction" to rs.getString("direction"),
                    "receivedAt" to rs.getObject("created_at", OffsetDateTime::class.java).toString(),
                    "receivedVia" to rs.getString("received_via"),
                    "fileName" to rs.getString("file_name"),
                    "mediaType" to rs.getString("media_type"),
                    "sizeBytes" to rs.getLong("size_bytes"),
                    "sha256" to rs.getString("sha256"),
                    "format" to rs.getString("format"),
                    "profile" to rs.getString("format_version"),
                    "docType" to rs.getString("doc_type"),
                    "status" to rs.getString("status"),
                    "duplicateOf" to rs.getString("duplicate_of"),
                    "tags" to (rs.getArray("tags")?.array as? Array<*>)?.map { it.toString() }.orEmpty(),
                    "note" to rs.getString("note"),
                    "invoice" to rs.getString("invoice_data")?.let<String, JsonNode>(json::readTree),
                    "validation" to validations[id].orEmpty(),
                    "history" to history[id].orEmpty(),
                    "comments" to comments[id].orEmpty(),
                )
            }.list()
    }
}
