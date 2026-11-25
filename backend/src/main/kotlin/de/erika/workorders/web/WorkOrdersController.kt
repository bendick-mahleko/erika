package de.erika.workorders.web

import de.erika.contract.api.WorkordersApi
import de.erika.contract.model.ChecklistTemplateInput
import de.erika.contract.model.ChecklistTemplateList
import de.erika.contract.model.Role
import de.erika.contract.model.SiteInput
import de.erika.contract.model.SiteList
import de.erika.contract.model.StaffLevelInput
import de.erika.contract.model.StaffLevelList
import de.erika.contract.model.StaffList
import de.erika.contract.model.StaffMember
import de.erika.contract.model.StaffSettings
import de.erika.contract.model.WorkOrderInput
import de.erika.contract.model.WorkOrderList
import de.erika.creator.CustomerDirectory
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.ErikaException
import de.erika.platform.web.Etags
import de.erika.platform.web.Idempotency
import de.erika.workorders.ChecklistItem
import de.erika.workorders.ChecklistItemKind
import de.erika.workorders.ChecklistTemplate
import de.erika.workorders.ChecklistTemplateRepository
import de.erika.workorders.Site
import de.erika.workorders.SiteRepository
import de.erika.workorders.StaffEntry
import de.erika.workorders.StaffLevel
import de.erika.workorders.StaffLevelRepository
import de.erika.workorders.WorkOrder
import de.erika.workorders.WorkOrderChange
import de.erika.workorders.WorkOrderErrors
import de.erika.workorders.WorkOrderFilter
import de.erika.workorders.WorkOrderRepository
import de.erika.workorders.WorkOrderStatus
import de.erika.workorders.WorkOrders
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import de.erika.contract.model.ChecklistItem as ApiChecklistItem
import de.erika.contract.model.ChecklistItemKind as ApiChecklistItemKind
import de.erika.contract.model.ChecklistTemplate as ApiChecklistTemplate
import de.erika.contract.model.Site as ApiSite
import de.erika.contract.model.StaffLevel as ApiStaffLevel
import de.erika.contract.model.WorkOrder as ApiWorkOrder
import de.erika.contract.model.WorkOrderStatus as ApiWorkOrderStatus

/**
 * MARA office endpoints (api/openapi.yaml, tag `workorders`, MARA Stage 2). Reading for every
 * web role; changes for OWNER and MEMBER; staff levels (prices) and staff settings for owners.
 * Technicians never reach these: they have no web session (ADR 0039).
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class WorkOrdersController(
    private val service: WorkOrders,
    private val sites: SiteRepository,
    private val levels: StaffLevelRepository,
    private val templates: ChecklistTemplateRepository,
    private val orders: WorkOrderRepository,
    private val customers: CustomerDirectory,
    private val idempotency: Idempotency,
) : WorkordersApi {
    // ---- sites

    override fun listSites(
        customerId: UUID?,
        includeArchived: Boolean,
    ): ResponseEntity<SiteList> = ResponseEntity.ok(SiteList(sites.list(customerId, includeArchived).map { it.toApi() }))

    override fun getSite(siteId: UUID): ResponseEntity<ApiSite> {
        val s = sites.find(siteId) ?: throw WorkOrderErrors.notFound("SITE")
        return ResponseEntity.ok().eTag(Etags.of(s.version)).body(s.toApi())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun createSite(
        idempotencyKey: String,
        siteInput: SiteInput,
    ): ResponseEntity<ApiSite> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createSite") {
                service.createSite(p.workspaceId, p.userId, siteInput.toModel(UUID.randomUUID()))
            }
        val s = sites.find(result.resourceId) ?: throw WorkOrderErrors.notFound("SITE")
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Etags.of(s.version)).body(s.toApi())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun updateSite(
        siteId: UUID,
        siteInput: SiteInput,
        ifMatch: String?,
    ): ResponseEntity<ApiSite> {
        val p = ErikaPrincipal.current()
        sites.find(siteId) ?: throw WorkOrderErrors.notFound("SITE")
        val saved = service.updateSite(p.workspaceId, p.userId, siteInput.toModel(siteId), Etags.required(ifMatch))
        return ResponseEntity.ok().eTag(Etags.of(saved.version)).body(saved.toApi())
    }

    // ---- staff levels and staff

    override fun listStaffLevels(includeArchived: Boolean): ResponseEntity<StaffLevelList> =
        ResponseEntity.ok(StaffLevelList(levels.list(includeArchived).map { it.toApi() }))

    override fun getStaffLevel(staffLevelId: UUID): ResponseEntity<ApiStaffLevel> {
        val l = levels.find(staffLevelId) ?: throw WorkOrderErrors.notFound("STAFF_LEVEL")
        return ResponseEntity.ok().eTag(Etags.of(l.version)).body(l.toApi())
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun createStaffLevel(
        idempotencyKey: String,
        staffLevelInput: StaffLevelInput,
    ): ResponseEntity<ApiStaffLevel> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createStaffLevel") {
                service.createStaffLevel(p.workspaceId, p.userId, staffLevelInput.toModel(UUID.randomUUID()))
            }
        val l = levels.find(result.resourceId) ?: throw WorkOrderErrors.notFound("STAFF_LEVEL")
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Etags.of(l.version)).body(l.toApi())
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun updateStaffLevel(
        staffLevelId: UUID,
        staffLevelInput: StaffLevelInput,
        ifMatch: String?,
    ): ResponseEntity<ApiStaffLevel> {
        val p = ErikaPrincipal.current()
        levels.find(staffLevelId) ?: throw WorkOrderErrors.notFound("STAFF_LEVEL")
        val saved = service.updateStaffLevel(p.workspaceId, p.userId, staffLevelInput.toModel(staffLevelId), Etags.required(ifMatch))
        return ResponseEntity.ok().eTag(Etags.of(saved.version)).body(saved.toApi())
    }

    override fun listStaff(): ResponseEntity<StaffList> = ResponseEntity.ok(StaffList(service.staff().map { it.toApi() }))

    @PreAuthorize("hasRole('OWNER')")
    override fun updateStaffSettings(
        memberId: UUID,
        staffSettings: StaffSettings,
    ): ResponseEntity<StaffMember> {
        val p = ErikaPrincipal.current()
        val entry = service.updateStaff(p.workspaceId, p.userId, memberId, staffSettings.staffLevelId, staffSettings.canSeePrices)
        return ResponseEntity.ok(entry.toApi())
    }

    // ---- checklist templates

    override fun listChecklistTemplates(includeArchived: Boolean): ResponseEntity<ChecklistTemplateList> =
        ResponseEntity.ok(ChecklistTemplateList(templates.list(includeArchived).map { it.toApi() }))

    override fun getChecklistTemplate(checklistTemplateId: UUID): ResponseEntity<ApiChecklistTemplate> {
        val t = templates.find(checklistTemplateId) ?: throw WorkOrderErrors.notFound("CHECKLIST_TEMPLATE")
        return ResponseEntity.ok().eTag(Etags.of(t.version)).body(t.toApi())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun createChecklistTemplate(
        idempotencyKey: String,
        checklistTemplateInput: ChecklistTemplateInput,
    ): ResponseEntity<ApiChecklistTemplate> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createChecklistTemplate") {
                service.createTemplate(p.workspaceId, p.userId, checklistTemplateInput.toModel(UUID.randomUUID()))
            }
        val t = templates.find(result.resourceId) ?: throw WorkOrderErrors.notFound("CHECKLIST_TEMPLATE")
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Etags.of(t.version)).body(t.toApi())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun updateChecklistTemplate(
        checklistTemplateId: UUID,
        checklistTemplateInput: ChecklistTemplateInput,
        ifMatch: String?,
    ): ResponseEntity<ApiChecklistTemplate> {
        val p = ErikaPrincipal.current()
        templates.find(checklistTemplateId) ?: throw WorkOrderErrors.notFound("CHECKLIST_TEMPLATE")
        val saved =
            service.updateTemplate(p.workspaceId, p.userId, checklistTemplateInput.toModel(checklistTemplateId), Etags.required(ifMatch))
        return ResponseEntity.ok().eTag(Etags.of(saved.version)).body(saved.toApi())
    }

    // ---- work orders

    override fun listWorkOrders(
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        assigneeId: UUID?,
        customerId: UUID?,
        status: ApiWorkOrderStatus?,
        limit: Int,
    ): ResponseEntity<WorkOrderList> {
        val list =
            orders.list(
                WorkOrderFilter(from, to, assigneeId, customerId, status?.let { WorkOrderStatus.valueOf(it.value) }, limit),
            )
        return ResponseEntity.ok(WorkOrderList(toApi(list)))
    }

    override fun getWorkOrder(workOrderId: UUID): ResponseEntity<ApiWorkOrder> {
        val w = orders.find(workOrderId) ?: throw WorkOrderErrors.notFound("WORK_ORDER")
        return ResponseEntity.ok().eTag(Etags.of(w.version)).body(toApi(listOf(w)).single())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun createWorkOrder(
        idempotencyKey: String,
        workOrderInput: WorkOrderInput,
    ): ResponseEntity<ApiWorkOrder> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createWorkOrder") {
                service.create(p.workspaceId, p.userId, UUID.randomUUID(), workOrderInput.toChange())
            }
        val w = orders.find(result.resourceId) ?: throw WorkOrderErrors.notFound("WORK_ORDER")
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Etags.of(w.version)).body(toApi(listOf(w)).single())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun updateWorkOrder(
        workOrderId: UUID,
        workOrderInput: WorkOrderInput,
        ifMatch: String?,
    ): ResponseEntity<ApiWorkOrder> {
        val p = ErikaPrincipal.current()
        orders.find(workOrderId) ?: throw WorkOrderErrors.notFound("WORK_ORDER")
        val saved = service.update(p.workspaceId, p.userId, workOrderId, workOrderInput.toChange(), Etags.required(ifMatch))
        return ResponseEntity.ok().eTag(Etags.of(saved.version)).body(toApi(listOf(saved)).single())
    }

    // ---- mapping

    private fun SiteInput.toModel(id: UUID) =
        Site(
            id = id,
            customerId = customerId,
            name = name.trim(),
            street = street.clean(),
            postalCode = postalCode.clean(),
            city = city.clean(),
            countryCode = countryCode,
            notes = notes.clean(),
            archived = archived ?: false,
        )

    private fun Site.toApi() =
        ApiSite(
            id = id,
            customerId = customerId,
            name = name,
            countryCode = countryCode,
            archived = archived,
            version = version,
            street = street,
            postalCode = postalCode,
            city = city,
            notes = notes,
        )

    private fun StaffLevelInput.toModel(id: UUID) =
        StaffLevel(id = id, name = name.trim(), hourlyRate = amount(hourlyRate, "hourlyRate"), archived = archived ?: false)

    private fun StaffLevel.toApi() =
        ApiStaffLevel(id = id, name = name, hourlyRate = hourlyRate.toPlainString(), archived = archived, version = version)

    private fun StaffEntry.toApi() =
        StaffMember(
            memberId = member.memberId,
            userId = member.userId,
            displayName = member.displayName,
            role = Role.forValue(member.role.name),
            canSeePrices = profile.canSeePrices,
            staffLevelId = profile.staffLevelId,
        )

    private fun ChecklistTemplateInput.toModel(id: UUID) =
        ChecklistTemplate(
            id = id,
            name = name.trim(),
            items = items.map { ChecklistItem(it.key, it.label.trim(), ChecklistItemKind.valueOf(it.kind.value), it.required) },
            archived = archived ?: false,
        )

    private fun ChecklistTemplate.toApi() =
        ApiChecklistTemplate(
            id = id,
            name = name,
            items = items.map { ApiChecklistItem(it.key, it.label, ApiChecklistItemKind.forValue(it.kind.name), it.required) },
            archived = archived,
            version = version,
        )

    private fun WorkOrderInput.toChange() =
        WorkOrderChange(
            customerId = customerId,
            siteId = siteId,
            title = title.trim(),
            description = description.clean(),
            plannedFrom = plannedFrom,
            plannedTo = plannedTo,
            assigneeIds = assigneeIds,
            checklistTemplateId = checklistTemplateId,
            status = status?.let { WorkOrderStatus.valueOf(it.value) },
        )

    /** Work orders with customer names and addresses, two queries for the whole list. */
    private fun toApi(list: List<WorkOrder>): List<ApiWorkOrder> {
        val customerById = customers.findAll(list.map { it.customerId })
        val siteById = sites.findAll(list.mapNotNull { it.siteId }).associateBy { it.id }
        return list.map { w ->
            val customer = customerById[w.customerId]
            val site = w.siteId?.let { siteById[it] }
            ApiWorkOrder(
                id = w.id,
                customerId = w.customerId,
                customerName = customer?.name ?: "",
                title = w.title,
                status = ApiWorkOrderStatus.forValue(w.status.name),
                assigneeIds = w.assigneeIds,
                version = w.version,
                createdAt = w.createdAt ?: OffsetDateTime.now(),
                updatedAt = w.updatedAt ?: OffsetDateTime.now(),
                siteId = w.siteId,
                siteName = site?.name,
                address =
                    site?.let {
                        address(
                            it.street,
                            it.postalCode,
                            it.city,
                        )
                    } ?: customer?.let { address(it.street, it.postalCode, it.city) },
                description = w.description,
                plannedFrom = w.plannedFrom,
                plannedTo = w.plannedTo,
                checklistTemplateId = w.checklistTemplateId,
            )
        }
    }

    private fun address(
        street: String?,
        postalCode: String?,
        city: String?,
    ): String? =
        listOfNotNull(street, listOfNotNull(postalCode, city).joinToString(" ").ifBlank { null })
            .joinToString(", ")
            .ifBlank { null }
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** A non-negative decimal from the API; the pattern is checked by bean validation. */
private fun amount(
    value: String,
    field: String,
): BigDecimal =
    value.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
        ?: throw ErikaException("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, "$field is not a valid amount.")
