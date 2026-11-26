package de.erika.workorders

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

private const val WS = "workspace_id = current_workspace_id()"

/** Two decimals at least, no trailing zeros beyond that ("58.00", "61.255"). */
internal fun money(value: BigDecimal): BigDecimal = value.stripTrailingZeros().let { if (it.scale() < 2) it.setScale(2) else it }

@Repository
internal class SiteRepository(
    private val jdbc: JdbcClient,
) {
    fun list(
        customerId: UUID?,
        includeArchived: Boolean,
    ): List<Site> =
        jdbc
            .sql(
                """
                SELECT * FROM site WHERE $WS
                   AND (CAST(:customer AS uuid) IS NULL OR customer_id = :customer)
                   AND (:archived OR NOT archived)
                 ORDER BY lower(name), id
                 LIMIT 1000
                """.trimIndent(),
            ).param("customer", customerId)
            .param("archived", includeArchived)
            .query(::map)
            .list()

    fun find(id: UUID): Site? =
        jdbc
            .sql("SELECT * FROM site WHERE id = :id AND $WS")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun findAll(ids: Collection<UUID>): List<Site> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("SELECT * FROM site WHERE id IN (:ids) AND $WS")
                .param("ids", ids.distinct())
                .query(::map)
                .list()
        }

    fun insert(
        workspaceId: UUID,
        s: Site,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO site (id, workspace_id, customer_id, name, street, postal_code, city, country_code, notes, archived)
                VALUES (:id, :ws, :customer, :name, :street, :postal, :city, :country, :notes, :archived)
                """.trimIndent(),
            ).bind(s)
            .param("ws", workspaceId)
            .update()
    }

    /** Optimistic update; null if [expectedVersion] is outdated. The customer never changes. */
    fun update(
        s: Site,
        expectedVersion: Int,
    ): Site? {
        val n =
            jdbc
                .sql(
                    """
                    UPDATE site SET name = :name, street = :street, postal_code = :postal, city = :city, country_code = :country,
                           notes = :notes, archived = :archived, version = version + 1, updated_at = now()
                     WHERE id = :id AND customer_id = :customer AND $WS AND version = :expected
                    """.trimIndent(),
                ).bind(s)
                .param("expected", expectedVersion)
                .update()
        return if (n == 1) find(s.id) else null
    }

    private fun JdbcClient.StatementSpec.bind(s: Site) =
        param("id", s.id)
            .param("customer", s.customerId)
            .param("name", s.name)
            .param("street", s.street)
            .param("postal", s.postalCode)
            .param("city", s.city)
            .param("country", s.countryCode)
            .param("notes", s.notes)
            .param("archived", s.archived)

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = Site(
        id = rs.getObject("id", UUID::class.java),
        customerId = rs.getObject("customer_id", UUID::class.java),
        name = rs.getString("name"),
        street = rs.getString("street"),
        postalCode = rs.getString("postal_code"),
        city = rs.getString("city"),
        countryCode = rs.getString("country_code"),
        notes = rs.getString("notes"),
        archived = rs.getBoolean("archived"),
        version = rs.getInt("version"),
    )
}

@Repository
internal class StaffLevelRepository(
    private val jdbc: JdbcClient,
) {
    fun list(includeArchived: Boolean): List<StaffLevel> =
        jdbc
            .sql("SELECT * FROM staff_level WHERE $WS AND (:archived OR NOT archived) ORDER BY lower(name), id")
            .param("archived", includeArchived)
            .query(::map)
            .list()

    fun find(id: UUID): StaffLevel? =
        jdbc
            .sql("SELECT * FROM staff_level WHERE id = :id AND $WS")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun insert(
        workspaceId: UUID,
        l: StaffLevel,
    ) {
        jdbc
            .sql("INSERT INTO staff_level (id, workspace_id, name, hourly_rate, archived) VALUES (:id, :ws, :name, :rate, :archived)")
            .param("id", l.id)
            .param("ws", workspaceId)
            .param("name", l.name)
            .param("rate", l.hourlyRate)
            .param("archived", l.archived)
            .update()
    }

    fun update(
        l: StaffLevel,
        expectedVersion: Int,
    ): StaffLevel? {
        val n =
            jdbc
                .sql(
                    """
                    UPDATE staff_level SET name = :name, hourly_rate = :rate, archived = :archived, version = version + 1, updated_at = now()
                     WHERE id = :id AND $WS AND version = :expected
                    """.trimIndent(),
                ).param("id", l.id)
                .param("name", l.name)
                .param("rate", l.hourlyRate)
                .param("archived", l.archived)
                .param("expected", expectedVersion)
                .update()
        return if (n == 1) find(l.id) else null
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = StaffLevel(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        hourlyRate = money(rs.getBigDecimal("hourly_rate")),
        archived = rs.getBoolean("archived"),
        version = rs.getInt("version"),
    )
}

@Repository
internal class StaffProfileRepository(
    private val jdbc: JdbcClient,
) {
    fun all(): Map<UUID, StaffProfile> =
        jdbc
            .sql("SELECT * FROM staff_profile WHERE $WS")
            .query(::map)
            .list()
            .associateBy { it.userId }

    fun upsert(
        workspaceId: UUID,
        p: StaffProfile,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO staff_profile (workspace_id, user_id, staff_level_id, can_see_prices) VALUES (:ws, :user, :level, :prices)
                ON CONFLICT (workspace_id, user_id)
                DO UPDATE SET staff_level_id = EXCLUDED.staff_level_id, can_see_prices = EXCLUDED.can_see_prices, updated_at = now()
                """.trimIndent(),
            ).param("ws", workspaceId)
            .param("user", p.userId)
            .param("level", p.staffLevelId)
            .param("prices", p.canSeePrices)
            .update()
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = StaffProfile(
        userId = rs.getObject("user_id", UUID::class.java),
        staffLevelId = rs.getObject("staff_level_id", UUID::class.java),
        canSeePrices = rs.getBoolean("can_see_prices"),
    )
}

@Repository
internal class ChecklistTemplateRepository(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) {
    private val itemsType = object : TypeReference<List<ChecklistItem>>() {}

    fun list(includeArchived: Boolean): List<ChecklistTemplate> =
        jdbc
            .sql("SELECT * FROM checklist_template WHERE $WS AND (:archived OR NOT archived) ORDER BY lower(name), id")
            .param("archived", includeArchived)
            .query(::map)
            .list()

    fun find(id: UUID): ChecklistTemplate? =
        jdbc
            .sql("SELECT * FROM checklist_template WHERE id = :id AND $WS")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun insert(
        workspaceId: UUID,
        t: ChecklistTemplate,
    ) {
        jdbc
            .sql(
                "INSERT INTO checklist_template (id, workspace_id, name, items, archived) VALUES (:id, :ws, :name, CAST(:items AS jsonb), :archived)",
            ).param("id", t.id)
            .param("ws", workspaceId)
            .param("name", t.name)
            .param("items", json.writeValueAsString(t.items))
            .param("archived", t.archived)
            .update()
    }

    fun update(
        t: ChecklistTemplate,
        expectedVersion: Int,
    ): ChecklistTemplate? {
        val n =
            jdbc
                .sql(
                    """
                    UPDATE checklist_template SET name = :name, items = CAST(:items AS jsonb), archived = :archived,
                           version = version + 1, updated_at = now()
                     WHERE id = :id AND $WS AND version = :expected
                    """.trimIndent(),
                ).param("id", t.id)
                .param("name", t.name)
                .param("items", json.writeValueAsString(t.items))
                .param("archived", t.archived)
                .param("expected", expectedVersion)
                .update()
        return if (n == 1) find(t.id) else null
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = ChecklistTemplate(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        items = json.readValue(rs.getString("items"), itemsType),
        archived = rs.getBoolean("archived"),
        version = rs.getInt("version"),
    )
}

@Repository
internal class WorkOrderRepository(
    private val jdbc: JdbcClient,
) {
    fun list(f: WorkOrderFilter): List<WorkOrder> {
        val rows =
            jdbc
                .sql(
                    """
                    SELECT w.* FROM work_order w
                     WHERE w.$WS
                       AND (CAST(:customer AS uuid) IS NULL OR w.customer_id = :customer)
                       AND (CAST(:status AS text) IS NULL OR w.status = :status)
                       AND (CAST(:from AS timestamptz) IS NULL OR coalesce(w.planned_to, w.planned_from) >= :from)
                       AND (CAST(:to AS timestamptz) IS NULL OR w.planned_from < :to)
                       AND (CAST(:assignee AS uuid) IS NULL OR EXISTS (
                            SELECT 1 FROM work_order_assignment a
                             WHERE a.work_order_id = w.id AND a.user_id = :assignee AND a.$WS))
                     ORDER BY w.planned_from NULLS FIRST, w.created_at, w.id
                     LIMIT :limit
                    """.trimIndent(),
                ).param("customer", f.customerId)
                .param("status", f.status?.name)
                .param("from", f.from)
                .param("to", f.to)
                .param("assignee", f.assigneeId)
                .param("limit", f.limit)
                .query(::map)
                .list()
        val assignees = assignees(rows.map { it.id })
        return rows.map { it.copy(assigneeIds = assignees[it.id].orEmpty()) }
    }

    fun find(id: UUID): WorkOrder? =
        jdbc
            .sql("SELECT * FROM work_order WHERE id = :id AND $WS")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)
            ?.let { it.copy(assigneeIds = assignees(listOf(it.id))[it.id].orEmpty()) }

    fun insert(
        workspaceId: UUID,
        w: WorkOrder,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO work_order (id, workspace_id, customer_id, site_id, title, description, planned_from, planned_to, status,
                                        checklist_template_id, created_by, work_notes)
                VALUES (:id, :ws, :customer, :site, :title, :description, :from, :to, :status, :template, :createdBy, :notes)
                """.trimIndent(),
            ).bind(w)
            .param("ws", workspaceId)
            .param("createdBy", w.createdBy)
            .update()
        replaceAssignees(workspaceId, w.id, w.assigneeIds)
    }

    /** Optimistic update; false if [expectedVersion] is outdated. */
    fun update(
        workspaceId: UUID,
        w: WorkOrder,
        expectedVersion: Int,
    ): Boolean {
        val n =
            jdbc
                .sql(
                    """
                    UPDATE work_order SET customer_id = :customer, site_id = :site, title = :title, description = :description,
                           planned_from = :from, planned_to = :to, status = :status, checklist_template_id = :template,
                           work_notes = :notes, version = version + 1, updated_at = now()
                     WHERE id = :id AND $WS AND version = :expected
                    """.trimIndent(),
                ).bind(w)
                .param("expected", expectedVersion)
                .update()
        if (n != 1) return false
        replaceAssignees(workspaceId, w.id, w.assigneeIds)
        return true
    }

    private fun replaceAssignees(
        workspaceId: UUID,
        workOrderId: UUID,
        userIds: List<UUID>,
    ) {
        jdbc
            .sql("DELETE FROM work_order_assignment WHERE work_order_id = :id AND $WS")
            .param("id", workOrderId)
            .update()
        userIds.distinct().forEach { user ->
            jdbc
                .sql("INSERT INTO work_order_assignment (workspace_id, work_order_id, user_id) VALUES (:ws, :id, :user)")
                .param("ws", workspaceId)
                .param("id", workOrderId)
                .param("user", user)
                .update()
        }
    }

    private fun assignees(ids: List<UUID>): Map<UUID, List<UUID>> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            jdbc
                .sql("SELECT work_order_id, user_id FROM work_order_assignment WHERE work_order_id IN (:ids) AND $WS ORDER BY user_id")
                .param("ids", ids)
                .query { rs, _ -> rs.getObject("work_order_id", UUID::class.java) to rs.getObject("user_id", UUID::class.java) }
                .list()
                .groupBy({ it.first }, { it.second })
        }

    private fun JdbcClient.StatementSpec.bind(w: WorkOrder) =
        param("id", w.id)
            .param("customer", w.customerId)
            .param("site", w.siteId)
            .param("title", w.title)
            .param("description", w.description)
            .param("from", w.plannedFrom)
            .param("to", w.plannedTo)
            .param("status", w.status.name)
            .param("template", w.checklistTemplateId)
            .param("notes", w.workNotes)

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = WorkOrder(
        id = rs.getObject("id", UUID::class.java),
        customerId = rs.getObject("customer_id", UUID::class.java),
        siteId = rs.getObject("site_id", UUID::class.java),
        title = rs.getString("title"),
        description = rs.getString("description"),
        plannedFrom = rs.getObject("planned_from", OffsetDateTime::class.java),
        plannedTo = rs.getObject("planned_to", OffsetDateTime::class.java),
        status = WorkOrderStatus.valueOf(rs.getString("status")),
        checklistTemplateId = rs.getObject("checklist_template_id", UUID::class.java),
        assigneeIds = emptyList(),
        createdBy = rs.getObject("created_by", UUID::class.java),
        version = rs.getInt("version"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
        workNotes = rs.getString("work_notes"),
    )

    fun allIds(): List<UUID> =
        jdbc
            .sql("SELECT id FROM work_order WHERE $WS ORDER BY id")
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    fun findAll(ids: Collection<UUID>): List<WorkOrder> {
        if (ids.isEmpty()) return emptyList()
        val rows =
            jdbc
                .sql("SELECT * FROM work_order WHERE id IN (:ids) AND $WS")
                .param("ids", ids)
                .query(::map)
                .list()
        val assignees = assignees(rows.map { it.id })
        return rows.map { it.copy(assigneeIds = assignees[it.id].orEmpty()) }
    }
}
