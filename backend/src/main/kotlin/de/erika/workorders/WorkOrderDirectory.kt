package de.erika.workorders

import de.erika.sync.SyncViewer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** What other modules (MARA's `reports`) may know about a work order. */
data class WorkOrderRef(
    val id: UUID,
    val customerId: UUID,
    val title: String,
    val status: WorkOrderStatus,
    val assigneeIds: List<UUID>,
    val createdBy: UUID?,
    val checklistTemplateId: UUID?,
    val plannedFrom: OffsetDateTime?,
    val plannedTo: OffsetDateTime?,
)

/**
 * Published API of the `workorders` module: work orders, their phone scope and checklist items
 * for other modules (MARA Stage 5). Runs in the caller's tenant transaction.
 */
@Service
class WorkOrderDirectory internal constructor(
    private val orders: WorkOrderRepository,
    private val templates: ChecklistTemplateRepository,
) {
    private val clock: Clock = Clock.systemUTC()

    @Transactional(propagation = Propagation.MANDATORY)
    fun find(id: UUID): WorkOrderRef? = findAll(listOf(id))[id]

    @Transactional(propagation = Propagation.MANDATORY)
    fun findAll(ids: Collection<UUID>): Map<UUID, WorkOrderRef> =
        orders.findAll(ids.distinct()).associate {
            it.id to
                WorkOrderRef(
                    it.id,
                    it.customerId,
                    it.title,
                    it.status,
                    it.assigneeIds,
                    it.createdBy,
                    it.checklistTemplateId,
                    it.plannedFrom,
                    it.plannedTo,
                )
        }

    /** The work orders among [ids] that [viewer] has on the phone (the work orders' own sync scope). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun visibleTo(
        ids: Collection<UUID>,
        viewer: SyncViewer,
    ): Set<UUID> =
        findAll(ids)
            .values
            .filter {
                PhoneScope.visible(
                    it.status.name,
                    (it.plannedTo ?: it.plannedFrom)?.toInstant(),
                    it.assigneeIds,
                    it.createdBy,
                    viewer,
                    clock.instant(),
                )
            }.map { it.id }
            .toSet()

    /** The items of a checklist template (archived templates too: old work orders keep theirs). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun checklistItems(templateId: UUID): List<ChecklistItem>? = templates.find(templateId)?.items
}

/** Which work orders a phone holds (ADR 0038): one rule for the work orders and their reports. */
internal object PhoneScope {
    private val FINISHED = setOf("DONE", "CANCELLED", "SIGNED", "INVOICED")
    private val WINDOW: Duration = Duration.ofDays(30)

    /**
     * Owners and members see every work order, technicians the ones assigned to them or created
     * by them; finished work orders that ended more than 30 days ago leave the phones.
     */
    fun visible(
        status: String,
        end: Instant?,
        assignees: List<UUID>,
        createdBy: UUID?,
        viewer: SyncViewer,
        now: Instant,
    ): Boolean {
        if (status in FINISHED && end != null && end.isBefore(now.minus(WINDOW))) return false
        return viewer.isOffice || viewer.userId in assignees || createdBy == viewer.userId
    }
}
