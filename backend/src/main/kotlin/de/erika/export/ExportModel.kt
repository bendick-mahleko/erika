package de.erika.export

import de.erika.platform.audit.AuditableEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class ExportKind { PERIOD, FULL }

enum class ExportScope { IN, OUT, ALL }

enum class ExportState { QUEUED, READY, FAILED, EXPIRED }

internal data class ExportRow(
    val id: UUID,
    val workspaceId: UUID,
    val kind: ExportKind,
    val from: LocalDate?,
    val to: LocalDate?,
    val scope: ExportScope,
    val state: ExportState,
    val requestedBy: UUID,
    val createdAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val expiresAt: OffsetDateTime?,
    val storageKey: String?,
    val fileName: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val documentCount: Int?,
) {
    /** READY but past its expiry counts as expired even before the clean-up has run. */
    fun effectiveState(now: OffsetDateTime): ExportState =
        if (state == ExportState.READY && expiresAt != null && !expiresAt.isAfter(now)) ExportState.EXPIRED else state
}

data class ExportRequested(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val exportId: UUID,
    val kind: ExportKind,
    val from: LocalDate?,
    val to: LocalDate?,
    val scope: ExportScope,
) : AuditableEvent {
    override val action = "export.requested"
    override val details
        get() =
            buildMap {
                put("exportId", exportId.toString())
                put("kind", kind.name)
                put("scope", scope.name)
                from?.let { put("from", it.toString()) }
                to?.let { put("to", it.toString()) }
            }
}

data class ExportCompleted(
    override val workspaceId: UUID,
    val exportId: UUID,
    val sha256: String,
    val documentCount: Int,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "export.completed"
    override val details
        get() = mapOf("exportId" to exportId.toString(), "sha256" to sha256, "documents" to documentCount.toString())
}

data class ExportFailed(
    override val workspaceId: UUID,
    val exportId: UUID,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "export.failed"
    override val details get() = mapOf("exportId" to exportId.toString())
}

/** Data left ERIKA: who downloaded which export (NF-10). */
data class ExportDownloaded(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val exportId: UUID,
) : AuditableEvent {
    override val action = "export.downloaded"
    override val details get() = mapOf("exportId" to exportId.toString())
}

data class DatevSettingsChanged(
    override val workspaceId: UUID,
    override val actorId: UUID,
) : AuditableEvent {
    override val action = "export.datev_settings.changed"
}

@Repository
internal class ExportRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        id: UUID,
        workspaceId: UUID,
        kind: ExportKind,
        from: LocalDate?,
        to: LocalDate?,
        scope: ExportScope,
        requestedBy: UUID,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO data_export (id, workspace_id, kind, period_from, period_to, scope, requested_by)
                VALUES (:id, :ws, :kind, :from, :to, :scope, :by)
                """.trimIndent(),
            ).param("id", id)
            .param("ws", workspaceId)
            .param("kind", kind.name)
            .param("from", from)
            .param("to", to)
            .param("scope", scope.name)
            .param("by", requestedBy)
            .update()
    }

    fun find(id: UUID): ExportRow? =
        jdbc
            .sql("SELECT * FROM data_export WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun recent(limit: Int): List<ExportRow> =
        jdbc
            .sql("SELECT * FROM data_export WHERE workspace_id = current_workspace_id() ORDER BY created_at DESC, id LIMIT :limit")
            .param("limit", limit)
            .query(::map)
            .list()

    fun queuedCount(): Int =
        jdbc
            .sql("SELECT count(*) FROM data_export WHERE workspace_id = current_workspace_id() AND state = 'QUEUED'")
            .query(Int::class.java)
            .single()

    fun markReady(
        id: UUID,
        storageKey: String,
        fileName: String,
        sizeBytes: Long,
        sha256: String,
        documentCount: Int,
        expiresAt: OffsetDateTime,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE data_export
                   SET state = 'READY', finished_at = now(), expires_at = :expires, storage_key = :key, file_name = :file,
                       size_bytes = :size, sha256 = :sha, document_count = :count, error = NULL
                 WHERE id = :id AND workspace_id = current_workspace_id() AND state = 'QUEUED'
                """.trimIndent(),
            ).param("id", id)
            .param("expires", expiresAt)
            .param("key", storageKey)
            .param("file", fileName)
            .param("size", sizeBytes)
            .param("sha", sha256)
            .param("count", documentCount)
            .update() == 1

    fun markFailed(
        id: UUID,
        error: String,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE data_export SET state = 'FAILED', finished_at = now(), error = :error
                 WHERE id = :id AND workspace_id = current_workspace_id() AND state = 'QUEUED'
                """.trimIndent(),
            ).param("id", id)
            .param("error", error.take(500))
            .update() == 1

    fun markExpired(id: UUID): Boolean =
        jdbc
            .sql("UPDATE data_export SET state = 'EXPIRED' WHERE id = :id AND workspace_id = current_workspace_id() AND state = 'READY'")
            .param("id", id)
            .update() == 1

    /** Expired exports of every workspace (IDs only), for the clean-up. */
    fun expired(): List<Pair<UUID, UUID>> =
        jdbc
            .sql("SELECT workspace_id, id FROM expired_exports()")
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }
            .list()

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = ExportRow(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        kind = ExportKind.valueOf(rs.getString("kind")),
        from = rs.getObject("period_from", LocalDate::class.java),
        to = rs.getObject("period_to", LocalDate::class.java),
        scope = ExportScope.valueOf(rs.getString("scope")),
        state = ExportState.valueOf(rs.getString("state")),
        requestedBy = rs.getObject("requested_by", UUID::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        finishedAt = rs.getObject("finished_at", OffsetDateTime::class.java),
        expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
        storageKey = rs.getString("storage_key"),
        fileName = rs.getString("file_name"),
        sizeBytes = rs.getObject("size_bytes") as Long?,
        sha256 = rs.getString("sha256"),
        documentCount = rs.getObject("document_count") as Int?,
    )
}

/** The saved DATEV settings with their version (0 = never saved). */
internal data class StoredDatevSettings(
    val config: DatevConfig,
    val version: Int,
)

@Repository
internal class DatevSettingsRepository(
    private val jdbc: JdbcClient,
) {
    fun current(): StoredDatevSettings =
        jdbc
            .sql("SELECT * FROM datev_settings WHERE workspace_id = current_workspace_id()")
            .query { rs, _ ->
                StoredDatevSettings(
                    DatevConfig(
                        consultantNumber = rs.getObject("consultant_number") as Int?,
                        clientNumber = rs.getObject("client_number") as Int?,
                        chart = DatevChart.valueOf(rs.getString("chart")),
                        fiscalYearStartMonth = rs.getInt("fiscal_year_start_month"),
                        accountLength = rs.getInt("account_length"),
                        expenseAccount = rs.getString("expense_account"),
                        revenueAccount = rs.getString("revenue_account"),
                        creditorAccount = rs.getString("creditor_account"),
                        debtorAccount = rs.getString("debtor_account"),
                    ),
                    rs.getInt("version"),
                )
            }.optional()
            .orElse(StoredDatevSettings(DatevConfig(null, null), 0))

    /** Saves if [expectedVersion] is still current (0 = not saved yet). Returns the new version, or null on a conflict. */
    fun save(
        workspaceId: UUID,
        expectedVersion: Int,
        c: DatevConfig,
    ): Int? {
        val sql =
            if (expectedVersion == 0) {
                """
                INSERT INTO datev_settings (workspace_id, consultant_number, client_number, chart, fiscal_year_start_month,
                                            account_length, expense_account, revenue_account, creditor_account, debtor_account, version)
                VALUES (:ws, :consultant, :client, :chart, :month, :length, :expense, :revenue, :creditor, :debtor, 1)
                ON CONFLICT (workspace_id) DO NOTHING
                RETURNING version
                """.trimIndent()
            } else {
                """
                UPDATE datev_settings
                   SET consultant_number = :consultant, client_number = :client, chart = :chart, fiscal_year_start_month = :month,
                       account_length = :length, expense_account = :expense, revenue_account = :revenue,
                       creditor_account = :creditor, debtor_account = :debtor, version = version + 1, updated_at = now()
                 WHERE workspace_id = :ws AND version = :version
                RETURNING version
                """.trimIndent()
            }
        return jdbc
            .sql(sql)
            .param("ws", workspaceId)
            .param("version", expectedVersion)
            .param("consultant", c.consultantNumber)
            .param("client", c.clientNumber)
            .param("chart", c.chart.name)
            .param("month", c.fiscalYearStartMonth)
            .param("length", c.accountLength)
            .param("expense", c.expenseAccount)
            .param("revenue", c.revenueAccount)
            .param("creditor", c.creditorAccount)
            .param("debtor", c.debtorAccount)
            .query(Int::class.java)
            .optional()
            .orElse(null)
    }
}
