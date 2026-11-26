package de.erika.creator

import de.erika.invoicecore.write.OutputFormat
import de.erika.validation.ValidationMessage
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

internal data class DraftRow(
    val id: UUID,
    val workspaceId: UUID,
    val kind: InvoiceKind,
    val state: DraftState,
    val originalInvoiceId: UUID?,
    val content: DraftContent,
    val issueError: List<ValidationMessage>?,
    val version: Int,
    val createdBy: UUID?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    /** Recipients to email right after issuing (system invoices, ADR 0033); empty for drafts made in the UI. */
    val sendTo: List<String> = emptyList(),
)

/** An issued invoice (`invoice`): never changes after issue. */
internal data class IssuedRow(
    val id: UUID,
    val workspaceId: UUID,
    val number: String,
    val kind: InvoiceKind,
    val typeCode: String,
    val format: OutputFormat,
    val documentId: UUID,
    val sha256: String,
    val fileName: String,
    val mediaType: String,
    val originalInvoiceId: UUID?,
    val customerId: UUID?,
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val buyerName: String,
    val buyerEmail: String?,
    val currency: String,
    val net: BigDecimal,
    val tax: BigDecimal,
    val gross: BigDecimal,
    val issuedBy: UUID?,
    val issuedAt: OffsetDateTime,
)

enum class DeliveryStatus { QUEUED, SENT, DELIVERED, BOUNCED, FAILED }

internal data class DeliveryRow(
    val id: UUID,
    val workspaceId: UUID,
    val invoiceId: UUID,
    val recipient: String,
    val subject: String,
    val message: String?,
    val status: DeliveryStatus,
    val messageId: String,
    val providerMessageId: String?,
    val emlSha256: String?,
    val detail: String?,
    val requestedBy: UUID?,
    val createdAt: OffsetDateTime,
    val sentAt: OffsetDateTime?,
)

/** One entry of the invoice list: a draft, possibly issued. */
internal data class ListedInvoice(
    val draft: DraftRow,
    val issued: IssuedRow?,
)

@Repository
internal class DraftRepository(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) {
    fun insert(
        id: UUID,
        workspaceId: UUID,
        kind: InvoiceKind,
        originalInvoiceId: UUID?,
        content: DraftContent,
        createdBy: UUID?,
        sendTo: List<String> = emptyList(),
    ) {
        jdbc
            .sql(
                """
                INSERT INTO invoice_draft (id, workspace_id, kind, original_invoice_id, data, created_by, send_to)
                VALUES (:id, :ws, :kind, :original, CAST(:data AS jsonb), :by, :sendTo)
                """.trimIndent(),
            ).param("id", id)
            .param("sendTo", sendTo.takeIf { it.isNotEmpty() }?.toTypedArray(), java.sql.Types.ARRAY)
            .param("ws", workspaceId)
            .param("kind", kind.name)
            .param("original", originalInvoiceId)
            .param("data", json.writeValueAsString(content))
            .param("by", createdBy)
            .update()
    }

    fun find(id: UUID): DraftRow? =
        jdbc
            .sql("SELECT * FROM invoice_draft WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    /** Locks the draft row for the issuing transaction. */
    fun lock(id: UUID): DraftRow? =
        jdbc
            .sql("SELECT * FROM invoice_draft WHERE id = :id AND workspace_id = current_workspace_id() FOR UPDATE")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    /** Saves a DRAFT if [expectedVersion] is current; null otherwise. */
    fun updateContent(
        id: UUID,
        expectedVersion: Int,
        content: DraftContent,
    ): DraftRow? {
        val n =
            jdbc
                .sql(
                    """
                    UPDATE invoice_draft SET data = CAST(:data AS jsonb), version = version + 1, updated_at = now()
                     WHERE id = :id AND workspace_id = current_workspace_id() AND state = 'DRAFT' AND version = :expected
                    """.trimIndent(),
                ).param("data", json.writeValueAsString(content))
                .param("id", id)
                .param("expected", expectedVersion)
                .update()
        return if (n == 1) find(id) else null
    }

    /** DRAFT → ISSUING; false if the draft is not (or no longer) a draft. */
    fun markIssuing(id: UUID): Boolean =
        jdbc
            .sql(
                """
                UPDATE invoice_draft SET state = 'ISSUING', issue_error = NULL, version = version + 1, updated_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id() AND state = 'DRAFT'
                """.trimIndent(),
            ).param("id", id)
            .update() == 1

    fun markIssued(id: UUID) {
        jdbc
            .sql(
                """
                UPDATE invoice_draft SET state = 'ISSUED', version = version + 1, updated_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id() AND state = 'ISSUING'
                """.trimIndent(),
            ).param("id", id)
            .update()
    }

    /** ISSUING → DRAFT with the reasons (validation messages), after a failed issue. */
    fun backToDraft(
        id: UUID,
        messages: List<ValidationMessage>,
    ) {
        jdbc
            .sql(
                """
                UPDATE invoice_draft SET state = 'DRAFT', issue_error = CAST(:error AS jsonb), version = version + 1, updated_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id() AND state = 'ISSUING'
                """.trimIndent(),
            ).param("error", json.writeValueAsString(messages))
            .param("id", id)
            .update()
    }

    fun delete(id: UUID): Boolean =
        jdbc
            .sql("DELETE FROM invoice_draft WHERE id = :id AND workspace_id = current_workspace_id() AND state = 'DRAFT'")
            .param("id", id)
            .update() == 1

    /** Newest first by last change; keyset pagination on (updated_at, id). */
    fun list(
        state: DraftState?,
        limit: Int,
        after: Pair<OffsetDateTime, UUID>?,
    ): List<ListedInvoice> {
        val where = mutableListOf("d.workspace_id = current_workspace_id()")
        val params = mutableMapOf<String, Any?>("limit" to limit)
        state?.let {
            where += "d.state = :state"
            params["state"] = it.name
        }
        after?.let {
            where += "(d.updated_at, d.id) < (:afterAt, :afterId)"
            params["afterAt"] = it.first
            params["afterId"] = it.second
        }
        var spec =
            jdbc.sql(
                """
                SELECT d.*, i.id AS i_id, i.number AS i_number, i.kind AS i_kind, i.type_code AS i_type_code, i.format AS i_format,
                       i.document_id AS i_document_id, i.sha256 AS i_sha256, i.file_name AS i_file_name, i.media_type AS i_media_type,
                       i.original_invoice_id AS i_original, i.customer_id AS i_customer, i.issue_date AS i_issue_date,
                       i.due_date AS i_due_date, i.buyer_name AS i_buyer_name, i.buyer_email AS i_buyer_email, i.currency AS i_currency,
                       i.net AS i_net, i.tax AS i_tax, i.gross AS i_gross, i.issued_by AS i_issued_by, i.issued_at AS i_issued_at
                  FROM invoice_draft d
                  LEFT JOIN invoice i ON i.workspace_id = d.workspace_id AND i.id = d.id
                 WHERE ${where.joinToString(" AND ")}
                 ORDER BY d.updated_at DESC, d.id DESC
                 LIMIT :limit
                """.trimIndent(),
            )
        params.forEach { (k, v) -> spec = spec.param(k, v) }
        return spec
            .query { rs, row ->
                ListedInvoice(map(rs, row), if (rs.getObject("i_id") != null) IssuedRepository.map(rs, "i_") else null)
            }.list()
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = DraftRow(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        kind = InvoiceKind.valueOf(rs.getString("kind")),
        state = DraftState.valueOf(rs.getString("state")),
        originalInvoiceId = rs.getObject("original_invoice_id", UUID::class.java),
        content = json.readValue(rs.getString("data"), DraftContent::class.java),
        issueError = rs.getString("issue_error")?.let { json.readValue(it, Array<ValidationMessage>::class.java).toList() },
        version = rs.getInt("version"),
        createdBy = rs.getObject("created_by", UUID::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
        sendTo = (rs.getArray("send_to")?.array as? Array<*>)?.map { it.toString() }.orEmpty(),
    )
}

@Repository
internal class IssuedRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(r: IssuedRow) {
        jdbc
            .sql(
                """
                INSERT INTO invoice (id, workspace_id, number, kind, type_code, format, document_id, sha256, file_name, media_type,
                                     original_invoice_id, customer_id, issue_date, due_date, buyer_name, buyer_email, currency,
                                     net, tax, gross, issued_by)
                VALUES (:id, :ws, :number, :kind, :typeCode, :format, :doc, :sha, :fileName, :mediaType, :original, :customer,
                        :issueDate, :dueDate, :buyerName, :buyerEmail, :currency, :net, :tax, :gross, :by)
                """.trimIndent(),
            ).param("id", r.id)
            .param("ws", r.workspaceId)
            .param("number", r.number)
            .param("kind", r.kind.name)
            .param("typeCode", r.typeCode)
            .param("format", r.format.name)
            .param("doc", r.documentId)
            .param("sha", r.sha256)
            .param("fileName", r.fileName)
            .param("mediaType", r.mediaType)
            .param("original", r.originalInvoiceId)
            .param("customer", r.customerId)
            .param("issueDate", r.issueDate)
            .param("dueDate", r.dueDate)
            .param("buyerName", r.buyerName)
            .param("buyerEmail", r.buyerEmail)
            .param("currency", r.currency)
            .param("net", r.net)
            .param("tax", r.tax)
            .param("gross", r.gross)
            .param("by", r.issuedBy)
            .update()
    }

    fun find(id: UUID): IssuedRow? =
        jdbc
            .sql("SELECT * FROM invoice WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query { rs, _ -> map(rs, "") }
            .optional()
            .orElse(null)

    /** Invoices with a due date before [date] (for the overdue list). */
    fun dueBefore(date: LocalDate): List<IssuedRow> =
        jdbc
            .sql("SELECT * FROM invoice WHERE workspace_id = current_workspace_id() AND due_date < :d ORDER BY due_date, number")
            .param("d", date)
            .query { rs, _ -> map(rs, "") }
            .list()

    fun numberTaken(number: String): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM invoice WHERE workspace_id = current_workspace_id() AND number = :n)")
            .param("n", number)
            .query(Boolean::class.java)
            .single()

    /** Credit notes, cancellations and corrections that reference [id]. */
    fun correctionsOf(id: UUID): List<IssuedRow> =
        jdbc
            .sql("SELECT * FROM invoice WHERE workspace_id = current_workspace_id() AND original_invoice_id = :id ORDER BY issued_at")
            .param("id", id)
            .query { rs, _ -> map(rs, "") }
            .list()

    companion object {
        fun map(
            rs: ResultSet,
            p: String,
        ) = IssuedRow(
            id = rs.getObject("${p}id", UUID::class.java),
            workspaceId = rs.getObject("workspace_id", UUID::class.java),
            number = rs.getString("${p}number"),
            kind = InvoiceKind.valueOf(rs.getString("${p}kind")),
            typeCode = rs.getString("${p}type_code"),
            format = OutputFormat.valueOf(rs.getString("${p}format")),
            documentId = rs.getObject("${p}document_id", UUID::class.java),
            sha256 = rs.getString("${p}sha256"),
            fileName = rs.getString("${p}file_name"),
            mediaType = rs.getString("${p}media_type"),
            originalInvoiceId = rs.getObject(if (p.isEmpty()) "original_invoice_id" else "${p}original", UUID::class.java),
            customerId = rs.getObject(if (p.isEmpty()) "customer_id" else "${p}customer", UUID::class.java),
            issueDate = rs.getObject("${p}issue_date", LocalDate::class.java),
            dueDate = rs.getObject("${p}due_date", LocalDate::class.java),
            buyerName = rs.getString("${p}buyer_name"),
            buyerEmail = rs.getString("${p}buyer_email"),
            currency = rs.getString("${p}currency"),
            net = rs.getBigDecimal("${p}net"),
            tax = rs.getBigDecimal("${p}tax"),
            gross = rs.getBigDecimal("${p}gross"),
            issuedBy = rs.getObject("${p}issued_by", UUID::class.java),
            issuedAt = rs.getObject("${p}issued_at", OffsetDateTime::class.java),
        )
    }
}

@Repository
internal class DeliveryRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        id: UUID,
        workspaceId: UUID,
        invoiceId: UUID,
        recipient: String,
        subject: String,
        message: String?,
        messageId: String,
        requestedBy: UUID?,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO invoice_delivery (id, workspace_id, invoice_id, recipient, subject, message, message_id, requested_by)
                VALUES (:id, :ws, :invoice, :to, :subject, :message, :messageId, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("ws", workspaceId)
            .param("invoice", invoiceId)
            .param("to", recipient)
            .param("subject", subject)
            .param("message", message)
            .param("messageId", messageId)
            .param("by", requestedBy)
            .update()
    }

    fun find(id: UUID): DeliveryRow? =
        jdbc
            .sql("SELECT * FROM invoice_delivery WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun ofInvoice(invoiceId: UUID): List<DeliveryRow> =
        jdbc
            .sql("SELECT * FROM invoice_delivery WHERE invoice_id = :id AND workspace_id = current_workspace_id() ORDER BY created_at, id")
            .param("id", invoiceId)
            .query(::map)
            .list()

    fun markSent(
        id: UUID,
        emlSha256: String,
        providerMessageId: String?,
    ) {
        jdbc
            .sql(
                """
                UPDATE invoice_delivery SET status = 'SENT', eml_sha256 = :sha, provider_message_id = :provider, sent_at = now(),
                       updated_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id() AND status = 'QUEUED'
                """.trimIndent(),
            ).param("sha", emlSha256)
            .param("provider", providerMessageId)
            .param("id", id)
            .update()
    }

    /** Records a delivery result; returns the previous status, or null if the delivery is unknown. */
    fun updateStatus(
        id: UUID,
        status: DeliveryStatus,
        detail: String?,
    ): DeliveryStatus? =
        jdbc
            .sql(
                """
                UPDATE invoice_delivery d SET status = :status, detail = coalesce(:detail, d.detail), updated_at = now()
                  FROM invoice_delivery old
                 WHERE d.id = :id AND d.workspace_id = current_workspace_id() AND old.id = d.id AND old.workspace_id = d.workspace_id
                RETURNING old.status
                """.trimIndent(),
            ).param("status", status.name)
            .param("detail", detail)
            .param("id", id)
            .query(String::class.java)
            .optional()
            .map(DeliveryStatus::valueOf)
            .orElse(null)

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = DeliveryRow(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        invoiceId = rs.getObject("invoice_id", UUID::class.java),
        recipient = rs.getString("recipient"),
        subject = rs.getString("subject"),
        message = rs.getString("message"),
        status = DeliveryStatus.valueOf(rs.getString("status")),
        messageId = rs.getString("message_id"),
        providerMessageId = rs.getString("provider_message_id"),
        emlSha256 = rs.getString("eml_sha256"),
        detail = rs.getString("detail"),
        requestedBy = rs.getObject("requested_by", UUID::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        sentAt = rs.getObject("sent_at", OffsetDateTime::class.java),
    )
}
