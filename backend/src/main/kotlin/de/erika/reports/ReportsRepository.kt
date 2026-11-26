package de.erika.reports

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

private const val WS = "workspace_id = current_workspace_id()"

/** The `reports` module's tables (V15). Runs in the caller's tenant transaction. */
@Repository
internal class ReportsRepository(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) {
    // ---- reports

    fun reports(ids: Collection<UUID>): List<Report> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql(
                    "SELECT * FROM report WHERE id IN (:ids) AND $WS",
                ).param("ids", ids)
                .query(::report)
                .list()
        }

    fun reportOf(workOrderId: UUID): Report? =
        jdbc
            .sql("SELECT * FROM report WHERE work_order_id = :wo AND $WS ORDER BY version DESC LIMIT 1")
            .param("wo", workOrderId)
            .query(::report)
            .optional()
            .orElse(null)

    fun reportIdsOf(workOrderId: UUID): List<UUID> =
        jdbc
            .sql(
                "SELECT id FROM report WHERE work_order_id = :wo AND $WS",
            ).param("wo", workOrderId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    fun insertReport(
        workspaceId: UUID,
        r: Report,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO report (id, workspace_id, work_order_id, version, state, work_description, created_by)
                VALUES (:id, :ws, :wo, :version, :state, :description, :createdBy)
                """.trimIndent(),
            ).param("id", r.id)
            .param("ws", workspaceId)
            .param("wo", r.workOrderId)
            .param("version", r.version)
            .param("state", r.state.name)
            .param("description", r.workDescription)
            .param("createdBy", r.createdBy)
            .update()
    }

    fun updateReport(r: Report) {
        jdbc
            .sql(
                """
                UPDATE report SET state = :state, work_description = :description, completed_at = :completedAt,
                       completed_by = :completedBy, updated_at = now()
                 WHERE id = :id AND $WS
                """.trimIndent(),
            ).param("id", r.id)
            .param("state", r.state.name)
            .param("description", r.workDescription)
            .param("completedAt", r.completedAt)
            .param("completedBy", r.completedBy)
            .update()
    }

    fun allIds(table: String): List<UUID> =
        jdbc
            .sql("SELECT id FROM ${checked(table)} WHERE $WS ORDER BY id")
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    /** IDs of [table]'s records (children) of the given reports, deleted ones included. */
    fun childIds(
        table: String,
        reportIds: Collection<UUID>,
    ): List<UUID> =
        if (reportIds.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("SELECT id FROM ${checked(table)} WHERE report_id IN (:ids) AND $WS")
                .param("ids", reportIds)
                .query(UUID::class.java)
                .list()
                .filterNotNull()
        }

    /** Soft delete ([deleted] = true) or restore of a child record. */
    fun markDeleted(
        table: String,
        id: UUID,
        deleted: Boolean,
    ) {
        jdbc
            .sql("UPDATE ${checked(table)} SET deleted_at = CASE WHEN :deleted THEN now() END, updated_at = now() WHERE id = :id AND $WS")
            .param("deleted", deleted)
            .param("id", id)
            .update()
    }

    private fun checked(table: String) = table.also { require(it in TABLES) { "unknown table $it" } }

    // ---- time entries

    fun timeEntries(ids: Collection<UUID>): List<TimeEntry> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql(
                    "SELECT * FROM time_entry WHERE id IN (:ids) AND $WS",
                ).param("ids", ids)
                .query(::time)
                .list()
        }

    fun timeEntriesOf(reportId: UUID): List<TimeEntry> =
        jdbc
            .sql("SELECT * FROM time_entry WHERE report_id = :r AND deleted_at IS NULL AND $WS ORDER BY started_at, id")
            .param("r", reportId)
            .query(::time)
            .list()

    /** Inserts [t] when [new], else updates it. */
    fun saveTime(
        workspaceId: UUID,
        t: TimeEntry,
        new: Boolean,
    ) {
        val sql =
            if (new) {
                """
                INSERT INTO time_entry (id, workspace_id, report_id, author_id, technician_id, kind, started_at, ended_at, note)
                VALUES (:id, :ws, :report, :author, :technician, :kind, :started, :ended, :note)
                """.trimIndent()
            } else {
                """
                UPDATE time_entry SET technician_id = :technician, kind = :kind, started_at = :started, ended_at = :ended,
                       note = :note, updated_at = now()
                 WHERE id = :id AND $WS
                """.trimIndent()
            }
        jdbc
            .sql(sql)
            .param("id", t.id)
            .param("ws", workspaceId)
            .param("report", t.reportId)
            .param("author", t.authorId)
            .param("technician", t.technicianId)
            .param("kind", t.kind)
            .param("started", t.startedAt)
            .param("ended", t.endedAt)
            .param("note", t.note)
            .update()
    }

    // ---- materials

    fun materials(ids: Collection<UUID>): List<MaterialLine> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("SELECT * FROM material_line WHERE id IN (:ids) AND $WS")
                .param("ids", ids)
                .query(::material)
                .list()
        }

    fun materialsOf(reportId: UUID): List<MaterialLine> =
        jdbc
            .sql("SELECT * FROM material_line WHERE report_id = :r AND deleted_at IS NULL AND $WS ORDER BY created_at, id")
            .param("r", reportId)
            .query(::material)
            .list()

    /** Inserts [m] when [new], else updates it. */
    fun saveMaterial(
        workspaceId: UUID,
        m: MaterialLine,
        new: Boolean,
    ) {
        val sql =
            if (new) {
                """
                INSERT INTO material_line (id, workspace_id, report_id, author_id, article_id, description, quantity, unit_code)
                VALUES (:id, :ws, :report, :author, :article, :description, :quantity, :unit)
                """.trimIndent()
            } else {
                """
                UPDATE material_line SET article_id = :article, description = :description, quantity = :quantity, unit_code = :unit,
                       updated_at = now()
                 WHERE id = :id AND $WS
                """.trimIndent()
            }
        jdbc
            .sql(sql)
            .param("id", m.id)
            .param("ws", workspaceId)
            .param("report", m.reportId)
            .param("author", m.authorId)
            .param("article", m.articleId)
            .param("description", m.description)
            .param("quantity", m.quantity)
            .param("unit", m.unitCode)
            .update()
    }

    // ---- Aufmaß

    fun aufmass(ids: Collection<UUID>): List<AufmassPosition> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("SELECT * FROM aufmass_position WHERE id IN (:ids) AND $WS")
                .param("ids", ids)
                .query(::position)
                .list()
        }

    fun aufmassOf(reportId: UUID): List<AufmassPosition> =
        jdbc
            .sql("SELECT * FROM aufmass_position WHERE report_id = :r AND deleted_at IS NULL AND $WS ORDER BY created_at, id")
            .param("r", reportId)
            .query(::position)
            .list()

    /** Inserts [p] when [new], else updates it. */
    fun saveAufmass(
        workspaceId: UUID,
        p: AufmassPosition,
        new: Boolean,
    ) {
        val sql =
            if (new) {
                """
                INSERT INTO aufmass_position (id, workspace_id, report_id, author_id, label, kind, length, width, height, count,
                                              expression, deductions, result, rule_version, unit_code)
                VALUES (:id, :ws, :report, :author, :label, :kind, :length, :width, :height, :count,
                        :expression, CAST(:deductions AS jsonb), :result, :rule, :unit)
                """.trimIndent()
            } else {
                """
                UPDATE aufmass_position SET label = :label, kind = :kind, length = :length, width = :width, height = :height,
                       count = :count, expression = :expression, deductions = CAST(:deductions AS jsonb),
                       result = :result, rule_version = :rule, unit_code = :unit, updated_at = now()
                 WHERE id = :id AND $WS
                """.trimIndent()
            }
        jdbc
            .sql(sql)
            .param("id", p.id)
            .param("ws", workspaceId)
            .param("report", p.reportId)
            .param("author", p.authorId)
            .param("label", p.label)
            .param("kind", p.kind)
            .param("length", p.length)
            .param("width", p.width)
            .param("height", p.height)
            .param("count", p.count)
            .param("expression", p.expression)
            .param("deductions", json.writeValueAsString(p.deductions.map(::deductionJson)))
            .param("result", p.result)
            .param("rule", p.ruleVersion)
            .param("unit", p.unitCode)
            .update()
    }

    // ---- checklist answers

    fun answers(ids: Collection<UUID>): List<ChecklistAnswer> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("SELECT * FROM checklist_answer WHERE id IN (:ids) AND $WS")
                .param("ids", ids)
                .query(::answer)
                .list()
        }

    fun answersOf(reportId: UUID): List<ChecklistAnswer> =
        jdbc
            .sql("SELECT * FROM checklist_answer WHERE report_id = :r AND $WS ORDER BY item_key")
            .param("r", reportId)
            .query(::answer)
            .list()

    /** Inserts [a] when [new], else updates it. */
    fun saveAnswer(
        workspaceId: UUID,
        a: ChecklistAnswer,
        new: Boolean,
    ) {
        val sql =
            if (new) {
                """
                INSERT INTO checklist_answer (id, workspace_id, report_id, item_key, value, author_id)
                VALUES (:id, :ws, :report, :key, :value, :author)
                """.trimIndent()
            } else {
                """
                UPDATE checklist_answer SET value = :value, author_id = :author, updated_at = now()
                 WHERE id = :id AND $WS
                """.trimIndent()
            }
        jdbc
            .sql(sql)
            .param("id", a.id)
            .param("ws", workspaceId)
            .param("report", a.reportId)
            .param("key", a.itemKey)
            .param("value", a.value)
            .param("author", a.authorId)
            .update()
    }

    // ---- mapping

    private fun report(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = Report(
        id = rs.getObject("id", UUID::class.java),
        workOrderId = rs.getObject("work_order_id", UUID::class.java),
        version = rs.getInt("version"),
        state = ReportState.valueOf(rs.getString("state")),
        workDescription = rs.getString("work_description"),
        completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
        completedBy = rs.getObject("completed_by", UUID::class.java),
        createdBy = rs.getObject("created_by", UUID::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
    )

    private fun time(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = TimeEntry(
        id = rs.getObject("id", UUID::class.java),
        reportId = rs.getObject("report_id", UUID::class.java),
        authorId = rs.getObject("author_id", UUID::class.java),
        technicianId = rs.getObject("technician_id", UUID::class.java),
        kind = rs.getString("kind"),
        startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
        endedAt = rs.getObject("ended_at", OffsetDateTime::class.java),
        note = rs.getString("note"),
        deleted = rs.getObject("deleted_at") != null,
    )

    private fun material(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = MaterialLine(
        id = rs.getObject("id", UUID::class.java),
        reportId = rs.getObject("report_id", UUID::class.java),
        authorId = rs.getObject("author_id", UUID::class.java),
        articleId = rs.getObject("article_id", UUID::class.java),
        description = rs.getString("description"),
        quantity = rs.getBigDecimal("quantity"),
        unitCode = rs.getString("unit_code"),
        deleted = rs.getObject("deleted_at") != null,
    )

    private fun position(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = AufmassPosition(
        id = rs.getObject("id", UUID::class.java),
        reportId = rs.getObject("report_id", UUID::class.java),
        authorId = rs.getObject("author_id", UUID::class.java),
        label = rs.getString("label"),
        kind = rs.getString("kind"),
        length = rs.getBigDecimal("length"),
        width = rs.getBigDecimal("width"),
        height = rs.getBigDecimal("height"),
        count = rs.getBigDecimal("count"),
        expression = rs.getString("expression"),
        deductions =
            json.readValue(rs.getString("deductions"), object : TypeReference<List<Map<String, String?>>>() {}).map {
                AufmassDeduction(
                    it["label"],
                    it["length"]?.let(::BigDecimal),
                    it["width"]?.let(::BigDecimal),
                    it["height"]?.let(::BigDecimal),
                    it["count"]?.let(::BigDecimal),
                )
            },
        result = rs.getBigDecimal("result"),
        ruleVersion = rs.getInt("rule_version"),
        unitCode = rs.getString("unit_code"),
        deleted = rs.getObject("deleted_at") != null,
    )

    private fun answer(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = ChecklistAnswer(
        id = rs.getObject("id", UUID::class.java),
        reportId = rs.getObject("report_id", UUID::class.java),
        itemKey = rs.getString("item_key"),
        value = rs.getString("value"),
        authorId = rs.getObject("author_id", UUID::class.java),
    )

    companion object {
        val TABLES = setOf("report", "time_entry", "material_line", "aufmass_position", "checklist_answer")

        /** A deduction as stored and synced: decimals as plain strings, missing values left out. */
        fun deductionJson(d: AufmassDeduction): Map<String, String> =
            buildMap {
                d.label?.let { put("label", it) }
                d.length?.let { put("length", it.toPlainString()) }
                d.width?.let { put("width", it.toPlainString()) }
                d.height?.let { put("height", it.toPlainString()) }
                d.count?.let { put("count", it.toPlainString()) }
            }
    }
}
