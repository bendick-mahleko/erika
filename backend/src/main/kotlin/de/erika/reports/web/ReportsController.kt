package de.erika.reports.web

import de.erika.contract.api.ReportsApi
import de.erika.contract.model.AufmassDeduction
import de.erika.contract.model.AufmassKind
import de.erika.contract.model.ChecklistItemKind
import de.erika.contract.model.PhotoCategory
import de.erika.contract.model.PhotoState
import de.erika.contract.model.ReportAufmassPosition
import de.erika.contract.model.ReportChecklistItem
import de.erika.contract.model.ReportMaterialLine
import de.erika.contract.model.ReportPhoto
import de.erika.contract.model.ReportState
import de.erika.contract.model.ReportSummary
import de.erika.contract.model.ReportTimeEntry
import de.erika.contract.model.TimeEntryKind
import de.erika.contract.model.WorkOrderReport
import de.erika.creator.ArticleDirectory
import de.erika.identity.WorkspaceContacts
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.web.ErikaException
import de.erika.reports.ReportMedia
import de.erika.reports.ReportsRepository
import de.erika.workorders.WorkOrderDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The job report of a work order for the office (api/openapi.yaml, tag `reports`, MARA Stage 5).
 * Read-only for every web role: reports are written in the MARA app through sync.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class ReportsController(
    private val repo: ReportsRepository,
    private val workOrders: WorkOrderDirectory,
    private val people: WorkspaceContacts,
    private val articles: ArticleDirectory,
    private val media: ObjectProvider<ReportMedia>,
) : ReportsApi {
    override fun getWorkOrderReport(workOrderId: UUID): ResponseEntity<WorkOrderReport> {
        val order =
            workOrders.find(workOrderId) ?: throw ErikaException("WORK_ORDER_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")
        val items = order.checklistTemplateId?.let { workOrders.checklistItems(it) }.orEmpty()
        val report = repo.reportOf(order.id)
        if (report == null) {
            val checklist =
                items.map {
                    ReportChecklistItem(
                        key = it.key,
                        label = it.label,
                        kind = ChecklistItemKind.forValue(it.kind.name),
                        required = it.required,
                    )
                }
            return ResponseEntity.ok(
                WorkOrderReport(
                    workOrderId = order.id,
                    timeEntries = emptyList(),
                    materials = emptyList(),
                    aufmass = emptyList(),
                    checklist = checklist,
                    photos = emptyList(),
                ),
            )
        }
        val times = repo.timeEntriesOf(report.id)
        val materials = repo.materialsOf(report.id)
        val answers = repo.answersOf(report.id).associateBy { it.itemKey }
        val photos = media.orderedStream().toList().flatMap { it.photos(report.id) }
        val names = people.displayNames(times.map { it.technicianId } + photos.map { it.authorId } + listOfNotNull(report.completedBy))
        val articleRefs = articles.findAll(materials.mapNotNull { it.articleId })
        return ResponseEntity.ok(
            WorkOrderReport(
                workOrderId = order.id,
                report =
                    ReportSummary(
                        id = report.id,
                        version = report.version,
                        state = ReportState.forValue(report.state.name),
                        workDescription = report.workDescription,
                        completedAt = report.completedAt,
                        completedByName = report.completedBy?.let { names[it] },
                    ),
                timeEntries =
                    times.map {
                        ReportTimeEntry(
                            id = it.id,
                            technicianId = it.technicianId,
                            technicianName = names[it.technicianId] ?: "?",
                            kind = TimeEntryKind.forValue(it.kind),
                            startedAt = it.startedAt,
                            endedAt = it.endedAt,
                            note = it.note,
                        )
                    },
                materials =
                    materials.map {
                        ReportMaterialLine(
                            id = it.id,
                            articleId = it.articleId,
                            articleNumber = it.articleId?.let { a -> articleRefs[a]?.articleNumber },
                            description = it.description,
                            quantity = it.quantity.toPlainString(),
                            unitCode = it.unitCode,
                        )
                    },
                aufmass =
                    repo.aufmassOf(report.id).map { p ->
                        ReportAufmassPosition(
                            id = p.id,
                            label = p.label,
                            kind = AufmassKind.forValue(p.kind),
                            length = p.length?.toPlainString(),
                            width = p.width?.toPlainString(),
                            height = p.height?.toPlainString(),
                            count = p.count?.toPlainString(),
                            expression = p.expression,
                            deductions =
                                p.deductions.map {
                                    AufmassDeduction(
                                        label = it.label,
                                        length = it.length?.toPlainString(),
                                        width = it.width?.toPlainString(),
                                        height = it.height?.toPlainString(),
                                        count = it.count?.toPlainString(),
                                    )
                                },
                            result = p.result.toPlainString(),
                            unitCode = p.unitCode,
                        )
                    },
                checklist =
                    items.map {
                        ReportChecklistItem(
                            key = it.key,
                            label = it.label,
                            kind = ChecklistItemKind.forValue(it.kind.name),
                            required = it.required,
                            value = answers[it.key]?.value,
                        )
                    },
                photos =
                    photos.map {
                        ReportPhoto(
                            id = it.id,
                            caption = it.caption,
                            category = PhotoCategory.forValue(it.category),
                            checklistItemKey = it.checklistItemKey,
                            state = PhotoState.forValue(it.state),
                            rejectReason = it.rejectReason?.let(ReportPhoto.RejectReason::forValue),
                            takenAt = it.takenAt,
                            authorName = names[it.authorId] ?: "?",
                            width = it.width,
                            height = it.height,
                        )
                    },
            ),
        )
    }
}
