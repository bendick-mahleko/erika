package de.erika.reports

import de.erika.creator.ArticleDirectory
import de.erika.identity.WorkspaceContacts
import de.erika.sync.ChangeFeed
import de.erika.sync.FieldPolicy
import de.erika.sync.SyncEntity
import de.erika.sync.SyncRejection
import de.erika.sync.SyncViewer
import de.erika.workorders.ChecklistItemKind
import de.erika.workorders.WorkOrderDirectory
import de.erika.workorders.WorkOrderRef
import de.erika.workorders.WorkOrderStatus
import de.erika.workorders.WorkOrdersChanged
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/*
 * MARA Stage 5: job reports as syncable records (ADR 0038). A report belongs to a work order and
 * is visible exactly where the work order is. Its child records (times, materials, Aufmaß,
 * checklist answers) each have one author, who changes or deletes them (owners and members
 * may too). Values on the wire: IDs, decimals and instants as strings.
 */

internal const val MAX_TEXT = 2_000
internal const val MAX_DESCRIPTION = 20_000
private val DECIMAL = Regex("""^(0|[1-9]\d{0,8})(\.\d{1,6})?$""")
private val UNIT = Regex("""^[A-Z0-9]{1,3}$""")

private fun invalid(field: String) = SyncRejection("INVALID_${field.uppercase()}")

private fun Map<String, Any?>.text(
    field: String,
    max: Int = MAX_TEXT,
): String? =
    when (val v = this[field]) {
        null -> null
        is String -> v.trim().ifEmpty { null }?.also { if (it.length > max) throw invalid(field) }
        else -> throw invalid(field)
    }

private fun Map<String, Any?>.uuid(field: String): UUID? =
    when (val v = this[field]) {
        null -> null
        is String -> runCatching { UUID.fromString(v) }.getOrNull() ?: throw invalid(field)
        else -> throw invalid(field)
    }

private fun Map<String, Any?>.instant(field: String): OffsetDateTime? =
    when (val v = this[field]) {
        null -> null
        is String -> runCatching { OffsetDateTime.parse(v) }.getOrNull() ?: throw invalid(field)
        else -> throw invalid(field)
    }

private fun decimal(
    value: Any?,
    field: String,
): BigDecimal? =
    when (value) {
        null -> null
        is String -> value.takeIf { DECIMAL.matches(it) }?.let(::BigDecimal) ?: throw invalid(field)
        else -> throw invalid(field)
    }

private fun Map<String, Any?>.decimal(field: String): BigDecimal? = decimal(this[field], field)

private fun OffsetDateTime?.wire(): String? = this?.toInstant()?.toString()

private fun BigDecimal?.wire(): String? = this?.toPlainString()

/** Scope, lock and author checks shared by the report entities. */
@Component
internal class ReportGuard(
    private val repo: ReportsRepository,
    private val workOrders: WorkOrderDirectory,
    private val media: ObjectProvider<ReportMedia>,
) {
    /** The report and its work order, if [viewer] may change it. */
    fun writable(
        reportId: UUID,
        viewer: SyncViewer,
    ): Pair<Report, WorkOrderRef> {
        val report = repo.reports(listOf(reportId)).firstOrNull() ?: throw SyncRejection("REPORT_NOT_FOUND")
        val order = workOrder(report.workOrderId, viewer)
        // A signed (or sent unsigned) version never changes; the database trigger refuses it too (M-MO-10).
        if (report.state.locked) throw SyncRejection("REPORT_LOCKED")
        return report to order
    }

    fun workOrder(
        workOrderId: UUID,
        viewer: SyncViewer,
    ): WorkOrderRef {
        val order = workOrders.find(workOrderId) ?: throw SyncRejection("WORK_ORDER_NOT_FOUND")
        if (order.id !in workOrders.visibleTo(listOf(order.id), viewer)) throw SyncRejection("NOT_IN_SCOPE")
        // An invoiced work order's reports don't change any more (corrections by credit note, Stage 8).
        if (order.status == WorkOrderStatus.INVOICED) throw SyncRejection("REPORT_LOCKED")
        return order
    }

    /** Mandatory checklist items answered (photo items: a photo exists, Stage 6), no timer running (M-MO-07). */
    fun checkCompletable(
        report: Report,
        order: WorkOrderRef,
    ) {
        if (repo.timeEntriesOf(report.id).any { it.endedAt == null }) throw SyncRejection("TIMER_RUNNING")
        val items = order.checklistTemplateId?.let { workOrders.checklistItems(it) }.orEmpty()
        val answered =
            repo
                .answersOf(report.id)
                .filter { !it.value.isNullOrBlank() }
                .map { it.itemKey }
                .toSet()
        val photographed =
            media
                .orderedStream()
                .toList()
                .flatMap { it.answeredItems(report.id) }
                .toSet()
        val missing =
            items.filter { item ->
                item.required && item.key !in (if (item.kind == ChecklistItemKind.PHOTO) photographed else answered)
            }
        if (missing.isNotEmpty()) throw SyncRejection("CHECKLIST_INCOMPLETE")
    }

    fun author(
        authorId: UUID,
        viewer: SyncViewer,
    ) {
        if (!viewer.isOffice && authorId != viewer.userId) throw SyncRejection("NOT_AUTHOR")
    }

    /** Records whose report (by `reportId` in the data) is on [viewer]'s phone. */
    fun viewChildren(
        data: Map<UUID, Map<String, Any?>>,
        viewer: SyncViewer,
    ): Map<UUID, Map<String, Any?>?> {
        val reportIds = data.values.map { UUID.fromString(it["reportId"] as String) }.toSet()
        val reports = repo.reports(reportIds).associateBy { it.id }
        val visible = workOrders.visibleTo(reports.values.map { it.workOrderId }, viewer)
        return data.mapValues { (_, d) ->
            val report = reports[UUID.fromString(d["reportId"] as String)]
            d.takeIf { report != null && report.workOrderId in visible }
        }
    }
}

/**
 * The report of a work order. Its ID is derived from the work order (`ids/derived.json`), so two
 * phones starting it offline create the same record. Completing it needs every mandatory
 * checklist item answered and no running timer (M-MO-07).
 */
@Component
internal class ReportSyncEntity(
    private val repo: ReportsRepository,
    private val guard: ReportGuard,
    private val workOrders: WorkOrderDirectory,
    private val events: ApplicationEventPublisher,
    private val media: ObjectProvider<ReportMedia>,
) : SyncEntity {
    override val name = NAME

    override val fields =
        mapOf(
            "workOrderId" to FieldPolicy.SERVER_WINS,
            "version" to FieldPolicy.SERVER_WINS,
            "workDescription" to FieldPolicy.LAST_WRITER_WINS,
            "state" to FieldPolicy.LAST_WRITER_WINS,
        )

    override fun allIds(): List<UUID> = repo.allIds("report")

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        repo.reports(ids).associate { r ->
            r.id to
                linkedMapOf(
                    "workOrderId" to r.workOrderId.toString(),
                    "version" to r.version,
                    "state" to r.state.name,
                    "workDescription" to r.workDescription,
                    "createdBy" to r.createdBy.toString(),
                    "basedOn" to r.basedOn?.toString(),
                    "completedAt" to r.completedAt.wire(),
                    "completedBy" to r.completedBy?.toString(),
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ): Map<String, Any?>? = viewAll(mapOf(UUID(0, 0) to data), viewer).values.first()

    override fun viewAll(
        data: Map<UUID, Map<String, Any?>>,
        viewer: SyncViewer,
    ): Map<UUID, Map<String, Any?>?> {
        val visible = workOrders.visibleTo(data.values.map { UUID.fromString(it["workOrderId"] as String) }, viewer)
        return data.mapValues { (_, d) -> d.takeIf { UUID.fromString(d["workOrderId"] as String) in visible } }
    }

    override fun mayWrite(
        field: String,
        viewer: SyncViewer,
    ) = field != "workOrderId" && field != "version"

    override fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val order = guard.workOrder(values.uuid("workOrderId") ?: throw SyncRejection("WORK_ORDER_REQUIRED"), viewer)
        val version = (values["version"] as? Number)?.toInt() ?: 1
        if (version < 1 || id != DerivedIds.report(order.id, version)) throw SyncRejection("INVALID_REPORT_ID")
        // A correction (M-BR-01) follows a signed or unsigned-sent version and starts as its copy.
        val previous =
            if (version == 1) {
                null
            } else {
                val prev =
                    repo.reports(listOf(DerivedIds.report(order.id, version - 1))).firstOrNull()
                        ?: throw SyncRejection("PREVIOUS_VERSION_MISSING")
                if (!prev.state.locked) throw SyncRejection("PREVIOUS_VERSION_NOT_SIGNED")
                prev
            }
        val report =
            Report(
                id = id,
                workOrderId = order.id,
                version = version,
                basedOn = previous?.id,
                state = ReportState.OPEN,
                workDescription = values.text("workDescription", MAX_DESCRIPTION),
                completedAt = null,
                completedBy = null,
                createdBy = viewer.userId,
                createdAt = null,
            )
        repo.insertReport(viewer.workspaceId, report)
        events.publishEvent(ReportsChanged(viewer.workspaceId, viewer.userId, NAME, id, if (previous == null) "created" else "corrected"))
        previous?.let { copyRecords(it, report, viewer) }
        values["state"]?.let { update(id, mapOf("state" to it), viewer) }
    }

    /** The correction starts with everything of the version it corrects; the records get new IDs. */
    private fun copyRecords(
        from: Report,
        to: Report,
        viewer: SyncViewer,
    ) {
        val ws = viewer.workspaceId

        fun created(
            kind: String,
            id: UUID,
        ) = events.publishEvent(ReportsChanged(ws, viewer.userId, kind, id, "created"))
        repo.timeEntriesOf(from.id).forEach {
            val copy = it.copy(id = UUID.randomUUID(), reportId = to.id)
            repo.saveTime(ws, copy, new = true)
            created(TimeEntrySyncEntity.NAME, copy.id)
        }
        repo.materialsOf(from.id).forEach {
            val copy = it.copy(id = UUID.randomUUID(), reportId = to.id)
            repo.saveMaterial(ws, copy, new = true)
            created(MaterialLineSyncEntity.NAME, copy.id)
        }
        repo.aufmassOf(from.id).forEach {
            val copy = it.copy(id = UUID.randomUUID(), reportId = to.id)
            repo.saveAufmass(ws, copy, new = true)
            created(AufmassPositionSyncEntity.NAME, copy.id)
        }
        repo.answersOf(from.id).forEach {
            val copy = it.copy(id = DerivedIds.checklistAnswer(to.id, it.itemKey), reportId = to.id)
            repo.saveAnswer(ws, copy, new = true)
            created(ChecklistAnswerSyncEntity.NAME, copy.id)
        }
        media.orderedStream().forEach { it.copyPhotos(from.id, to.id, ws, viewer.userId) }
    }

    override fun update(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val (current, order) = guard.writable(id, viewer)
        var next = current
        if ("workDescription" in values) next = next.copy(workDescription = values.text("workDescription", MAX_DESCRIPTION))
        var change = "changed"
        if ("state" in values) {
            // SIGNED and NOT_SIGNED come only with a signature record (report_signature).
            val state =
                (values["state"] as? String)?.let { s -> listOf(ReportState.OPEN, ReportState.COMPLETED).firstOrNull { it.name == s } }
                    ?: throw invalid("state")
            if (state == ReportState.COMPLETED && current.state != ReportState.COMPLETED) {
                guard.checkCompletable(current, order)
                next = next.copy(state = state, completedAt = OffsetDateTime.now(), completedBy = viewer.userId)
                change = "completed"
            } else if (state == ReportState.OPEN && current.state != ReportState.OPEN) {
                next = next.copy(state = state, completedAt = null, completedBy = null)
                change = "reopened"
            }
        }
        if (next == current) return
        repo.updateReport(next)
        events.publishEvent(ReportsChanged(viewer.workspaceId, viewer.userId, NAME, id, change))
    }

    companion object {
        const val NAME = "report"
    }
}

/** Times for work and travel (M-MO-03): raw instants, several technicians per job. */
@Component
internal class TimeEntrySyncEntity(
    private val repo: ReportsRepository,
    private val guard: ReportGuard,
    private val people: WorkspaceContacts,
    private val events: ApplicationEventPublisher,
) : SyncEntity {
    override val name = NAME

    override val fields =
        listOf("reportId", "technicianId", "kind", "startedAt", "endedAt", "note").associateWith { FieldPolicy.LAST_WRITER_WINS }

    override fun allIds(): List<UUID> = repo.allIds("time_entry")

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        repo.timeEntries(ids).associate { t ->
            t.id to
                linkedMapOf(
                    "reportId" to t.reportId.toString(),
                    "authorId" to t.authorId.toString(),
                    "technicianId" to t.technicianId.toString(),
                    "kind" to t.kind,
                    "startedAt" to t.startedAt.wire(),
                    "endedAt" to t.endedAt.wire(),
                    "note" to t.note,
                    SyncEntity.DELETED to t.deleted,
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ) = guard.viewChildren(mapOf(UUID(0, 0) to data), viewer).values.first()

    override fun viewAll(
        data: Map<UUID, Map<String, Any?>>,
        viewer: SyncViewer,
    ) = guard.viewChildren(data, viewer)

    override fun mayWrite(
        field: String,
        viewer: SyncViewer,
    ) = field != "reportId"

    override fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val (report, _) = guard.writable(values.uuid("reportId") ?: throw SyncRejection("REPORT_REQUIRED"), viewer)
        val entry =
            TimeEntry(
                id = id,
                reportId = report.id,
                authorId = viewer.userId,
                technicianId = values.uuid("technicianId") ?: viewer.userId,
                kind = values.text("kind") ?: "WORK",
                startedAt = values.instant("startedAt") ?: throw SyncRejection("INVALID_STARTEDAT"),
                endedAt = values.instant("endedAt"),
                note = values.text("note"),
            )
        save(entry, viewer, new = true)
    }

    override fun update(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val current = repo.timeEntries(listOf(id)).firstOrNull() ?: throw SyncRejection("NOT_FOUND")
        guard.writable(current.reportId, viewer)
        guard.author(current.authorId, viewer)
        val next =
            current.copy(
                technicianId = if ("technicianId" in values) values.uuid("technicianId") ?: viewer.userId else current.technicianId,
                kind = if ("kind" in values) values.text("kind") ?: throw invalid("kind") else current.kind,
                startedAt = if ("startedAt" in values) values.instant("startedAt") ?: throw invalid("startedAt") else current.startedAt,
                endedAt = if ("endedAt" in values) values.instant("endedAt") else current.endedAt,
                note = if ("note" in values) values.text("note") else current.note,
            )
        save(next, viewer, new = false)
    }

    private fun save(
        entry: TimeEntry,
        viewer: SyncViewer,
        new: Boolean,
    ) {
        if (entry.kind !in KINDS) throw invalid("kind")
        if (entry.endedAt != null && entry.endedAt.isBefore(entry.startedAt)) throw SyncRejection("TIME_RANGE_INVALID")
        if (people.workforce().none { it.userId == entry.technicianId }) throw SyncRejection("TECHNICIAN_INVALID")
        repo.saveTime(viewer.workspaceId, entry, new)
        events.publishEvent(ReportsChanged(viewer.workspaceId, viewer.userId, NAME, entry.id, if (new) "created" else "changed"))
    }

    override fun delete(
        id: UUID,
        viewer: SyncViewer,
    ) = softDelete(repo, guard, events, NAME, "time_entry", id, viewer) {
        repo.timeEntries(listOf(it)).firstOrNull()?.let { t ->
            t.reportId to
                t.authorId
        }
    }

    override fun restore(
        id: UUID,
        viewer: SyncViewer,
    ) = softRestore(repo, guard, events, NAME, "time_entry", id, viewer) { repo.timeEntries(listOf(it)).firstOrNull()?.reportId }

    companion object {
        const val NAME = "time_entry"
        val KINDS = setOf("WORK", "TRAVEL")
    }
}

/** Materials (M-MO-04): from the article list or free text, with quantity and unit. */
@Component
internal class MaterialLineSyncEntity(
    private val repo: ReportsRepository,
    private val guard: ReportGuard,
    private val articles: ArticleDirectory,
    private val events: ApplicationEventPublisher,
) : SyncEntity {
    override val name = NAME

    override val fields =
        listOf("reportId", "articleId", "description", "quantity", "unitCode").associateWith { FieldPolicy.LAST_WRITER_WINS }

    override fun allIds(): List<UUID> = repo.allIds("material_line")

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        repo.materials(ids).associate { m ->
            m.id to
                linkedMapOf(
                    "reportId" to m.reportId.toString(),
                    "authorId" to m.authorId.toString(),
                    "articleId" to m.articleId?.toString(),
                    "description" to m.description,
                    "quantity" to m.quantity.wire(),
                    "unitCode" to m.unitCode,
                    SyncEntity.DELETED to m.deleted,
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ) = guard.viewChildren(mapOf(UUID(0, 0) to data), viewer).values.first()

    override fun viewAll(
        data: Map<UUID, Map<String, Any?>>,
        viewer: SyncViewer,
    ) = guard.viewChildren(data, viewer)

    override fun mayWrite(
        field: String,
        viewer: SyncViewer,
    ) = field != "reportId"

    override fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val (report, _) = guard.writable(values.uuid("reportId") ?: throw SyncRejection("REPORT_REQUIRED"), viewer)
        val line =
            MaterialLine(
                id = id,
                reportId = report.id,
                authorId = viewer.userId,
                articleId = values.uuid("articleId"),
                description = values.text("description", 500) ?: throw SyncRejection("DESCRIPTION_REQUIRED"),
                quantity = values.decimal("quantity") ?: throw invalid("quantity"),
                unitCode = values.text("unitCode") ?: throw invalid("unitCode"),
            )
        save(line, viewer, new = true)
    }

    override fun update(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val current = repo.materials(listOf(id)).firstOrNull() ?: throw SyncRejection("NOT_FOUND")
        guard.writable(current.reportId, viewer)
        guard.author(current.authorId, viewer)
        val next =
            current.copy(
                articleId = if ("articleId" in values) values.uuid("articleId") else current.articleId,
                description =
                    if ("description" in
                        values
                    ) {
                        values.text("description", 500) ?: throw SyncRejection("DESCRIPTION_REQUIRED")
                    } else {
                        current.description
                    },
                quantity = if ("quantity" in values) values.decimal("quantity") ?: throw invalid("quantity") else current.quantity,
                unitCode = if ("unitCode" in values) values.text("unitCode") ?: throw invalid("unitCode") else current.unitCode,
            )
        save(next, viewer, new = false)
    }

    private fun save(
        line: MaterialLine,
        viewer: SyncViewer,
        new: Boolean,
    ) {
        if (line.quantity.signum() <= 0) throw invalid("quantity")
        if (!UNIT.matches(line.unitCode)) throw invalid("unitCode")
        line.articleId?.let { if (articles.findAll(listOf(it)).isEmpty()) throw SyncRejection("ARTICLE_NOT_FOUND") }
        repo.saveMaterial(viewer.workspaceId, line, new)
        events.publishEvent(ReportsChanged(viewer.workspaceId, viewer.userId, NAME, line.id, if (new) "created" else "changed"))
    }

    override fun delete(
        id: UUID,
        viewer: SyncViewer,
    ) = softDelete(repo, guard, events, NAME, "material_line", id, viewer) {
        repo.materials(listOf(it)).firstOrNull()?.let { m ->
            m.reportId to
                m.authorId
        }
    }

    override fun restore(
        id: UUID,
        viewer: SyncViewer,
    ) = softRestore(repo, guard, events, NAME, "material_line", id, viewer) { repo.materials(listOf(it)).firstOrNull()?.reportId }

    companion object {
        const val NAME = "material_line"
    }
}

/** Aufmaß positions (M-MO-06); the server calculates the result with the shared rules. */
@Component
internal class AufmassPositionSyncEntity(
    private val repo: ReportsRepository,
    private val guard: ReportGuard,
    private val events: ApplicationEventPublisher,
) : SyncEntity {
    override val name = NAME

    override val fields =
        listOf("reportId", "label", "kind", "length", "width", "height", "count", "expression", "deductions", "unitCode")
            .associateWith { FieldPolicy.LAST_WRITER_WINS }

    override fun allIds(): List<UUID> = repo.allIds("aufmass_position")

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        repo.aufmass(ids).associate { p ->
            p.id to
                linkedMapOf(
                    "reportId" to p.reportId.toString(),
                    "authorId" to p.authorId.toString(),
                    "label" to p.label,
                    "kind" to p.kind,
                    "length" to p.length.wire(),
                    "width" to p.width.wire(),
                    "height" to p.height.wire(),
                    "count" to p.count.wire(),
                    "expression" to p.expression,
                    "deductions" to p.deductions.map(ReportsRepository::deductionJson),
                    "unitCode" to p.unitCode,
                    "result" to p.result.wire(),
                    "ruleVersion" to p.ruleVersion,
                    SyncEntity.DELETED to p.deleted,
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ) = guard.viewChildren(mapOf(UUID(0, 0) to data), viewer).values.first()

    override fun viewAll(
        data: Map<UUID, Map<String, Any?>>,
        viewer: SyncViewer,
    ) = guard.viewChildren(data, viewer)

    override fun mayWrite(
        field: String,
        viewer: SyncViewer,
    ) = field != "reportId"

    override fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val (report, _) = guard.writable(values.uuid("reportId") ?: throw SyncRejection("REPORT_REQUIRED"), viewer)
        val empty =
            AufmassPosition(
                id = id,
                reportId = report.id,
                authorId = viewer.userId,
                label = "",
                kind = "AREA",
                length = null,
                width = null,
                height = null,
                count = null,
                expression = null,
                deductions = emptyList(),
                result = BigDecimal.ZERO,
                ruleVersion = AufmassRules.VERSION,
                unitCode = "MTK",
            )
        save(merge(empty, values), viewer, new = true)
    }

    override fun update(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val current = repo.aufmass(listOf(id)).firstOrNull() ?: throw SyncRejection("NOT_FOUND")
        guard.writable(current.reportId, viewer)
        guard.author(current.authorId, viewer)
        save(merge(current, values), viewer, new = false)
    }

    private fun merge(
        p: AufmassPosition,
        values: Map<String, Any?>,
    ) = p.copy(
        label = if ("label" in values) values.text("label", 200) ?: throw SyncRejection("LABEL_REQUIRED") else p.label,
        kind = if ("kind" in values) values.text("kind") ?: throw invalid("kind") else p.kind,
        length = if ("length" in values) values.decimal("length") else p.length,
        width = if ("width" in values) values.decimal("width") else p.width,
        height = if ("height" in values) values.decimal("height") else p.height,
        count = if ("count" in values) values.decimal("count") else p.count,
        expression = if ("expression" in values) values.text("expression", AufmassRules.MAX_FORMULA) else p.expression,
        deductions = if ("deductions" in values) deductions(values["deductions"]) else p.deductions,
        unitCode = if ("unitCode" in values) values.text("unitCode") ?: throw invalid("unitCode") else p.unitCode,
    )

    private fun deductions(value: Any?): List<AufmassDeduction> {
        val list = value as? List<*> ?: if (value == null) return emptyList() else throw invalid("deductions")
        if (list.size > 50) throw invalid("deductions")
        return list.map { d ->
            @Suppress("UNCHECKED_CAST")
            val m = d as? Map<String, Any?> ?: throw invalid("deductions")
            AufmassDeduction(
                label = m.text("label", 200),
                length = decimal(m["length"], "deductions"),
                width = decimal(m["width"], "deductions"),
                height = decimal(m["height"], "deductions"),
                count = decimal(m["count"], "deductions"),
            )
        }
    }

    private fun save(
        p: AufmassPosition,
        viewer: SyncViewer,
        new: Boolean,
    ) {
        if (p.label.isBlank()) throw SyncRejection("LABEL_REQUIRED")
        if (!UNIT.matches(p.unitCode)) throw invalid("unitCode")
        val result =
            try {
                AufmassRules.position(
                    AufmassInput(
                        kind = p.kind,
                        length = p.length,
                        width = p.width,
                        height = p.height,
                        count = p.count,
                        expression = p.expression,
                        deductions = p.deductions.map { AufmassDeductionInput(it.length, it.width, it.height, it.count) },
                    ),
                )
            } catch (e: AufmassException) {
                throw SyncRejection(e.code)
            }
        val saved = p.copy(result = result, ruleVersion = AufmassRules.VERSION)
        repo.saveAufmass(viewer.workspaceId, saved, new)
        events.publishEvent(ReportsChanged(viewer.workspaceId, viewer.userId, NAME, p.id, if (new) "created" else "changed"))
    }

    override fun delete(
        id: UUID,
        viewer: SyncViewer,
    ) = softDelete(repo, guard, events, NAME, "aufmass_position", id, viewer) {
        repo.aufmass(listOf(it)).firstOrNull()?.let { p ->
            p.reportId to
                p.authorId
        }
    }

    override fun restore(
        id: UUID,
        viewer: SyncViewer,
    ) = softRestore(repo, guard, events, NAME, "aufmass_position", id, viewer) { repo.aufmass(listOf(it)).firstOrNull()?.reportId }

    companion object {
        const val NAME = "aufmass_position"
    }
}

/**
 * Checklist answers (M-MO-07). The ID is derived from report and item key, so two phones
 * answering the same item offline write to the same record (the later write wins, with a note).
 */
@Component
internal class ChecklistAnswerSyncEntity(
    private val repo: ReportsRepository,
    private val guard: ReportGuard,
    private val workOrders: WorkOrderDirectory,
    private val events: ApplicationEventPublisher,
) : SyncEntity {
    override val name = NAME

    override val fields =
        mapOf(
            "reportId" to FieldPolicy.LAST_WRITER_WINS,
            "itemKey" to FieldPolicy.LAST_WRITER_WINS,
            "value" to FieldPolicy.LAST_WRITER_WINS,
        )

    override fun allIds(): List<UUID> = repo.allIds("checklist_answer")

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        repo.answers(ids).associate { a ->
            a.id to
                linkedMapOf(
                    "reportId" to a.reportId.toString(),
                    "itemKey" to a.itemKey,
                    "value" to a.value,
                    "authorId" to a.authorId.toString(),
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ) = guard.viewChildren(mapOf(UUID(0, 0) to data), viewer).values.first()

    override fun viewAll(
        data: Map<UUID, Map<String, Any?>>,
        viewer: SyncViewer,
    ) = guard.viewChildren(data, viewer)

    override fun mayWrite(
        field: String,
        viewer: SyncViewer,
    ) = field == "value"

    override fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val (report, order) = guard.writable(values.uuid("reportId") ?: throw SyncRejection("REPORT_REQUIRED"), viewer)
        val key = values.text("itemKey", 100) ?: throw invalid("itemKey")
        if (id != DerivedIds.checklistAnswer(report.id, key)) throw SyncRejection("INVALID_ANSWER_ID")
        val answer = ChecklistAnswer(id, report.id, key, null, viewer.userId)
        save(answer.copy(value = value(order, key, values)), viewer, new = true)
    }

    override fun update(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val current = repo.answers(listOf(id)).firstOrNull() ?: throw SyncRejection("NOT_FOUND")
        val (_, order) = guard.writable(current.reportId, viewer)
        save(current.copy(value = value(order, current.itemKey, values), authorId = viewer.userId), viewer, new = false)
    }

    private fun value(
        order: WorkOrderRef,
        key: String,
        values: Map<String, Any?>,
    ): String? {
        val item =
            order.checklistTemplateId?.let { workOrders.checklistItems(it) }?.firstOrNull { it.key == key }
                ?: throw SyncRejection("CHECKLIST_ITEM_UNKNOWN")
        val value = values.text("value")
        if (item.kind == ChecklistItemKind.YES_NO && value != null && value !in setOf("YES", "NO")) throw invalid("value")
        return value
    }

    private fun save(
        answer: ChecklistAnswer,
        viewer: SyncViewer,
        new: Boolean,
    ) {
        repo.saveAnswer(viewer.workspaceId, answer, new)
        events.publishEvent(ReportsChanged(viewer.workspaceId, viewer.userId, NAME, answer.id, if (new) "created" else "changed"))
    }

    companion object {
        const val NAME = "checklist_answer"
    }
}

private fun softDelete(
    repo: ReportsRepository,
    guard: ReportGuard,
    events: ApplicationEventPublisher,
    kind: String,
    table: String,
    id: UUID,
    viewer: SyncViewer,
    find: (UUID) -> Pair<UUID, UUID>?,
) {
    val (reportId, authorId) = find(id) ?: throw SyncRejection("NOT_FOUND")
    guard.writable(reportId, viewer)
    guard.author(authorId, viewer)
    repo.markDeleted(table, id, true)
    events.publishEvent(ReportsChanged(viewer.workspaceId, viewer.userId, kind, id, "deleted"))
}

/** An edit wins against a delete (ADR 0038): anyone who may edit the report brings the record back. */
private fun softRestore(
    repo: ReportsRepository,
    guard: ReportGuard,
    events: ApplicationEventPublisher,
    kind: String,
    table: String,
    id: UUID,
    viewer: SyncViewer,
    find: (UUID) -> UUID?,
) {
    guard.writable(find(id) ?: throw SyncRejection("NOT_FOUND"), viewer)
    repo.markDeleted(table, id, false)
    events.publishEvent(ReportsChanged(viewer.workspaceId, viewer.userId, kind, id, "restored"))
}

/**
 * Every audited change of this module goes into the change feed (ADR 0038). A changed work order
 * (e.g. reassigned) sends its report again, so it reaches new assignees and leaves old ones.
 */
@Component
internal class ReportsFeed(
    private val feed: ChangeFeed,
    private val repo: ReportsRepository,
) {
    @EventListener
    fun on(event: ReportsChanged) = feed.changed(event.kind, event.id)

    @EventListener
    fun on(event: WorkOrdersChanged) {
        if (event.kind != "work_order" || event.created) return
        val reports = repo.reportIdsOf(event.id)
        if (reports.isEmpty()) return
        feed.changedAll(ReportSyncEntity.NAME, reports)
        for ((table, entity) in CHILDREN) feed.changedAll(entity, repo.childIds(table, reports))
    }

    private companion object {
        val CHILDREN =
            listOf(
                "time_entry" to TimeEntrySyncEntity.NAME,
                "material_line" to MaterialLineSyncEntity.NAME,
                "aufmass_position" to AufmassPositionSyncEntity.NAME,
                "checklist_answer" to ChecklistAnswerSyncEntity.NAME,
                "report_signature" to ReportSignatureSyncEntity.NAME,
            )
    }
}
