package de.erika.workorders

import de.erika.platform.audit.AuditableEvent
import de.erika.platform.web.ErikaException
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** A job site: where the work is, for one customer (M-OF-01). */
data class Site(
    val id: UUID,
    val customerId: UUID,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val countryCode: String,
    val notes: String?,
    val archived: Boolean,
    val version: Int = 0,
)

/** A staff level with its hourly rate (M-OF-04). */
data class StaffLevel(
    val id: UUID,
    val name: String,
    val hourlyRate: BigDecimal,
    val archived: Boolean,
    val version: Int = 0,
)

/** Staff level and price visibility of one person in the workspace (ADR 0039). */
data class StaffProfile(
    val userId: UUID,
    val staffLevelId: UUID?,
    val canSeePrices: Boolean,
)

enum class ChecklistItemKind { YES_NO, VALUE, PHOTO, COMMENT }

data class ChecklistItem(
    val key: String,
    val label: String,
    val kind: ChecklistItemKind,
    val required: Boolean,
)

/** A checklist template per job type (M-OF-05). */
data class ChecklistTemplate(
    val id: UUID,
    val name: String,
    val items: List<ChecklistItem>,
    val archived: Boolean,
    val version: Int = 0,
)

enum class WorkOrderStatus {
    PLANNED,
    IN_PROGRESS,
    DONE,
    SIGNED,
    INVOICED,
    CANCELLED,
    ;

    /** Set by users; SIGNED and INVOICED are set by MARA itself (Stages 7 and 8). */
    val settableByUser: Boolean get() = this != SIGNED && this != INVOICED

    /** Signed or invoiced work orders don't change any more. */
    val locked: Boolean get() = this == SIGNED || this == INVOICED
}

/** A work order (Auftrag, M-OF-02). "Job" is the background job in ERIKA, hence the name. */
data class WorkOrder(
    val id: UUID,
    val customerId: UUID,
    val siteId: UUID?,
    val title: String,
    val description: String?,
    val plannedFrom: OffsetDateTime?,
    val plannedTo: OffsetDateTime?,
    val status: WorkOrderStatus,
    val checklistTemplateId: UUID?,
    val assigneeIds: List<UUID>,
    val createdBy: UUID?,
    val version: Int = 0,
    /** Notes the technicians write in the app (M-MO-01); the technician wins on conflicts. */
    val workNotes: String? = null,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null,
)

/** What a user sends to create or change a work order. */
data class WorkOrderChange(
    val customerId: UUID,
    val siteId: UUID?,
    val title: String,
    val description: String?,
    val plannedFrom: OffsetDateTime?,
    val plannedTo: OffsetDateTime?,
    val assigneeIds: List<UUID>,
    val checklistTemplateId: UUID?,
    /** Null keeps the current status (PLANNED for a new work order). */
    val status: WorkOrderStatus?,
)

/** Filter for the list and the calendar. */
data class WorkOrderFilter(
    val from: OffsetDateTime? = null,
    val to: OffsetDateTime? = null,
    val assigneeId: UUID? = null,
    val customerId: UUID? = null,
    val status: WorkOrderStatus? = null,
    val limit: Int = 200,
)

/**
 * A site, staff level, staff profile, checklist template or work order was created or changed.
 * Only which record is audited, not its content (customer data, NF-10).
 */
data class WorkOrdersChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val kind: String,
    val id: UUID,
    val created: Boolean,
) : AuditableEvent {
    override val action = if (created) "workorders.$kind.created" else "workorders.$kind.changed"
    override val details get() = mapOf("id" to id.toString())
}

/** A work order's status changed (M-OF-02). */
data class WorkOrderStatusChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val workOrderId: UUID,
    val from: WorkOrderStatus?,
    val to: WorkOrderStatus,
) : AuditableEvent {
    override val action = "workorders.work_order.status_changed"
    override val details get() = mapOf("id" to workOrderId.toString(), "from" to (from?.name ?: ""), "to" to to.name)
}

/** The people assigned to a work order changed (M-OF-03). */
data class WorkOrderAssigned(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val workOrderId: UUID,
    val assigneeIds: List<UUID>,
) : AuditableEvent {
    override val action = "workorders.work_order.assigned"
    override val details get() = mapOf("id" to workOrderId.toString(), "assignees" to assigneeIds.sorted().joinToString(","))
}

internal object WorkOrderErrors {
    fun notFound(what: String) = ErikaException("${what}_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")

    fun invalid(
        code: String,
        detail: String,
    ) = ErikaException(code, HttpStatus.BAD_REQUEST, detail)

    fun conflict(
        code: String,
        detail: String,
    ) = ErikaException(code, HttpStatus.CONFLICT, detail)
}
