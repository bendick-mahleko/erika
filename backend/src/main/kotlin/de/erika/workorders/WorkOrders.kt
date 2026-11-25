package de.erika.workorders

import de.erika.creator.CustomerDirectory
import de.erika.identity.WorkforceMember
import de.erika.identity.WorkspaceContacts
import de.erika.platform.web.Etags
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A person who can be assigned, with their staff settings. */
data class StaffEntry(
    val member: WorkforceMember,
    val profile: StaffProfile,
)

/**
 * Sites, staff levels, staff settings, checklist templates and work orders (MARA Stage 2).
 * Runs in the request's tenant transaction. Customers come from `creator`, people from
 * `identity`, both through their published APIs (ADR 0036).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
internal class WorkOrders(
    private val sites: SiteRepository,
    private val levels: StaffLevelRepository,
    private val profiles: StaffProfileRepository,
    private val templates: ChecklistTemplateRepository,
    private val orders: WorkOrderRepository,
    private val customers: CustomerDirectory,
    private val people: WorkspaceContacts,
    private val events: ApplicationEventPublisher,
) {
    // ---- sites

    fun createSite(
        workspaceId: UUID,
        actorId: UUID,
        site: Site,
    ): UUID {
        val customer = customers.find(site.customerId) ?: throw WorkOrderErrors.invalid("CUSTOMER_NOT_FOUND", "The customer doesn't exist.")
        if (customer.archived) throw WorkOrderErrors.invalid("CUSTOMER_ARCHIVED", "The customer is archived.")
        sites.insert(workspaceId, site)
        events.publishEvent(WorkOrdersChanged(workspaceId, actorId, "site", site.id, created = true))
        return site.id
    }

    fun updateSite(
        workspaceId: UUID,
        actorId: UUID,
        site: Site,
        expectedVersion: Int,
    ): Site {
        val current = sites.find(site.id) ?: throw WorkOrderErrors.notFound("SITE")
        if (current.customerId != site.customerId) {
            throw WorkOrderErrors.invalid("SITE_CUSTOMER_FIXED", "A site stays with its customer. Create a new site instead.")
        }
        val saved = sites.update(site, expectedVersion) ?: throw Etags.conflict()
        events.publishEvent(WorkOrdersChanged(workspaceId, actorId, "site", site.id, created = false))
        return saved
    }

    // ---- staff levels and staff settings

    fun createStaffLevel(
        workspaceId: UUID,
        actorId: UUID,
        level: StaffLevel,
    ): UUID {
        levels.insert(workspaceId, level)
        events.publishEvent(WorkOrdersChanged(workspaceId, actorId, "staff_level", level.id, created = true))
        return level.id
    }

    fun updateStaffLevel(
        workspaceId: UUID,
        actorId: UUID,
        level: StaffLevel,
        expectedVersion: Int,
    ): StaffLevel {
        levels.find(level.id) ?: throw WorkOrderErrors.notFound("STAFF_LEVEL")
        val saved = levels.update(level, expectedVersion) ?: throw Etags.conflict()
        events.publishEvent(WorkOrdersChanged(workspaceId, actorId, "staff_level", level.id, created = false))
        return saved
    }

    fun staff(): List<StaffEntry> {
        val all = profiles.all()
        return people.workforce().map { StaffEntry(it, all[it.userId] ?: StaffProfile(it.userId, null, false)) }
    }

    fun updateStaff(
        workspaceId: UUID,
        actorId: UUID,
        memberId: UUID,
        staffLevelId: UUID?,
        canSeePrices: Boolean,
    ): StaffEntry {
        val member = people.workforce().firstOrNull { it.memberId == memberId } ?: throw WorkOrderErrors.notFound("MEMBER")
        if (staffLevelId != null) {
            val level = levels.find(staffLevelId)
            if (level == null || level.archived) throw WorkOrderErrors.invalid("STAFF_LEVEL_NOT_FOUND", "The staff level doesn't exist.")
        }
        val profile = StaffProfile(member.userId, staffLevelId, canSeePrices)
        profiles.upsert(workspaceId, profile)
        events.publishEvent(WorkOrdersChanged(workspaceId, actorId, "staff", member.userId, created = false))
        return StaffEntry(member, profile)
    }

    // ---- checklist templates

    fun createTemplate(
        workspaceId: UUID,
        actorId: UUID,
        template: ChecklistTemplate,
    ): UUID {
        checkItems(template.items)
        templates.insert(workspaceId, template)
        events.publishEvent(WorkOrdersChanged(workspaceId, actorId, "checklist_template", template.id, created = true))
        return template.id
    }

    fun updateTemplate(
        workspaceId: UUID,
        actorId: UUID,
        template: ChecklistTemplate,
        expectedVersion: Int,
    ): ChecklistTemplate {
        templates.find(template.id) ?: throw WorkOrderErrors.notFound("CHECKLIST_TEMPLATE")
        checkItems(template.items)
        val saved = templates.update(template, expectedVersion) ?: throw Etags.conflict()
        events.publishEvent(WorkOrdersChanged(workspaceId, actorId, "checklist_template", template.id, created = false))
        return saved
    }

    private fun checkItems(items: List<ChecklistItem>) {
        val duplicates = items.groupBy { it.key }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw WorkOrderErrors.invalid(
                "CHECKLIST_KEYS_DUPLICATE",
                "Every item needs its own key: ${duplicates.sorted().joinToString()}.",
            )
        }
    }

    // ---- work orders

    fun create(
        workspaceId: UUID,
        actorId: UUID,
        id: UUID,
        change: WorkOrderChange,
    ): UUID {
        val status = change.status ?: WorkOrderStatus.PLANNED
        if (!status.settableByUser) throw statusNotAllowed()
        check(change, current = null)
        val order =
            WorkOrder(
                id = id,
                customerId = change.customerId,
                siteId = change.siteId,
                title = change.title,
                description = change.description,
                plannedFrom = change.plannedFrom,
                plannedTo = change.plannedTo,
                status = status,
                checklistTemplateId = change.checklistTemplateId,
                assigneeIds = change.assigneeIds.distinct(),
                createdBy = actorId,
            )
        orders.insert(workspaceId, order)
        events.publishEvent(WorkOrdersChanged(workspaceId, actorId, "work_order", id, created = true))
        events.publishEvent(WorkOrderStatusChanged(workspaceId, actorId, id, from = null, to = status))
        if (order.assigneeIds.isNotEmpty()) events.publishEvent(WorkOrderAssigned(workspaceId, actorId, id, order.assigneeIds))
        return id
    }

    fun update(
        workspaceId: UUID,
        actorId: UUID,
        id: UUID,
        change: WorkOrderChange,
        expectedVersion: Int,
    ): WorkOrder {
        val current = orders.find(id) ?: throw WorkOrderErrors.notFound("WORK_ORDER")
        if (current.status.locked) {
            throw WorkOrderErrors.conflict("WORK_ORDER_LOCKED", "A signed or invoiced work order can't be changed.")
        }
        val status = change.status ?: current.status
        if (status != current.status && !status.settableByUser) throw statusNotAllowed()
        check(change, current)
        val updated =
            current.copy(
                customerId = change.customerId,
                siteId = change.siteId,
                title = change.title,
                description = change.description,
                plannedFrom = change.plannedFrom,
                plannedTo = change.plannedTo,
                status = status,
                checklistTemplateId = change.checklistTemplateId,
                assigneeIds = change.assigneeIds.distinct(),
            )
        if (!orders.update(workspaceId, updated, expectedVersion)) throw Etags.conflict()
        events.publishEvent(WorkOrdersChanged(workspaceId, actorId, "work_order", id, created = false))
        if (status != current.status) events.publishEvent(WorkOrderStatusChanged(workspaceId, actorId, id, current.status, status))
        if (updated.assigneeIds.toSet() != current.assigneeIds.toSet()) {
            events.publishEvent(WorkOrderAssigned(workspaceId, actorId, id, updated.assigneeIds))
        }
        return orders.find(id) ?: throw WorkOrderErrors.notFound("WORK_ORDER")
    }

    private fun check(
        change: WorkOrderChange,
        current: WorkOrder?,
    ) {
        val customer =
            customers.find(change.customerId) ?: throw WorkOrderErrors.invalid("CUSTOMER_NOT_FOUND", "The customer doesn't exist.")
        if (customer.archived && current?.customerId != change.customerId) {
            throw WorkOrderErrors.invalid("CUSTOMER_ARCHIVED", "The customer is archived.")
        }
        change.siteId?.let { siteId ->
            val site = sites.find(siteId) ?: throw WorkOrderErrors.invalid("SITE_NOT_FOUND", "The site doesn't exist.")
            if (site.customerId != change.customerId) {
                throw WorkOrderErrors.invalid("SITE_NOT_FOR_CUSTOMER", "The site belongs to another customer.")
            }
        }
        change.checklistTemplateId?.let {
            templates.find(it) ?: throw WorkOrderErrors.invalid("CHECKLIST_TEMPLATE_NOT_FOUND", "The checklist template doesn't exist.")
        }
        val from = change.plannedFrom
        val to = change.plannedTo
        if ((to != null && from == null) || (from != null && to != null && to.isBefore(from))) {
            throw WorkOrderErrors.invalid("PLANNED_TIME_INVALID", "The end must not be before the start.")
        }
        if (change.assigneeIds.isNotEmpty()) {
            val allowed = people.workforce().map { it.userId }.toSet()
            if (!allowed.containsAll(change.assigneeIds)) {
                throw WorkOrderErrors.invalid("ASSIGNEE_INVALID", "Only owners, members and technicians of this workspace can be assigned.")
            }
        }
    }

    private fun statusNotAllowed() =
        WorkOrderErrors.conflict("STATUS_NOT_ALLOWED", "SIGNED and INVOICED are set by MARA when a report is signed or billed.")
}
