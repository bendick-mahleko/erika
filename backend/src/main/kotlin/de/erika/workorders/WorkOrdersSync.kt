package de.erika.workorders

import de.erika.creator.CustomerDirectory
import de.erika.identity.WorkspaceContacts
import de.erika.platform.mail.Mail
import de.erika.platform.mail.MailProperties
import de.erika.platform.mail.Outbox
import de.erika.platform.security.WorkspaceRole
import de.erika.sync.ChangeFeed
import de.erika.sync.FieldPolicy
import de.erika.sync.PriceVisibility
import de.erika.sync.SyncEntity
import de.erika.sync.SyncRejection
import de.erika.sync.SyncViewer
import org.springframework.context.MessageSource
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

/*
 * MARA Stage 4: work orders and their reference data as syncable records (ADR 0038). Values are
 * JSON-friendly: IDs, decimals and times as strings (times as UTC instants), lists sorted.
 */

private fun OffsetDateTime?.wire(): String? = this?.toInstant()?.toString()

private fun Any?.instant(field: String): OffsetDateTime? =
    when (this) {
        null -> null
        is String -> runCatching { OffsetDateTime.parse(this) }.getOrNull() ?: throw SyncRejection("INVALID_${field.uppercase()}")
        else -> throw SyncRejection("INVALID_${field.uppercase()}")
    }

private fun Any?.uuid(field: String): UUID? =
    when (this) {
        null -> null
        is String -> runCatching { UUID.fromString(this) }.getOrNull() ?: throw SyncRejection("INVALID_${field.uppercase()}")
        else -> throw SyncRejection("INVALID_${field.uppercase()}")
    }

private fun Any?.text(field: String): String? =
    when (this) {
        null -> null
        is String -> this
        else -> throw SyncRejection("INVALID_${field.uppercase()}")
    }

/**
 * Work orders (M-MO-01, M-MO-02). Scope: owners and members see all, technicians the ones
 * assigned to them or created by them; done with 30 days ago leaves the phones. Office wins
 * the planning fields, the technician wins status and work notes (design §4).
 */
@Component
internal class WorkOrderSyncEntity(
    private val orders: WorkOrderRepository,
    private val service: WorkOrders,
    private val customers: CustomerDirectory,
    private val people: WorkspaceContacts,
    private val outbox: Outbox,
    private val messages: MessageSource,
    private val mail: MailProperties,
) : SyncEntity {
    private val clock: Clock = Clock.systemUTC()

    override val name = NAME

    override val fields =
        mapOf(
            "customerId" to FieldPolicy.SERVER_WINS,
            "siteId" to FieldPolicy.SERVER_WINS,
            "title" to FieldPolicy.SERVER_WINS,
            "description" to FieldPolicy.SERVER_WINS,
            "plannedFrom" to FieldPolicy.SERVER_WINS,
            "plannedTo" to FieldPolicy.SERVER_WINS,
            "assigneeIds" to FieldPolicy.SERVER_WINS,
            "checklistTemplateId" to FieldPolicy.SERVER_WINS,
            "status" to FieldPolicy.DEVICE_WINS,
            "workNotes" to FieldPolicy.DEVICE_WINS,
        )

    override fun allIds(): List<UUID> = orders.allIds()

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        orders.findAll(ids).associate { w ->
            w.id to
                linkedMapOf(
                    "customerId" to w.customerId.toString(),
                    "siteId" to w.siteId?.toString(),
                    "title" to w.title,
                    "description" to w.description,
                    "plannedFrom" to w.plannedFrom.wire(),
                    "plannedTo" to w.plannedTo.wire(),
                    "status" to w.status.name,
                    "assigneeIds" to w.assigneeIds.map { it.toString() }.sorted(),
                    "checklistTemplateId" to w.checklistTemplateId?.toString(),
                    "workNotes" to w.workNotes,
                    "createdBy" to w.createdBy?.toString(),
                    "createdAt" to w.createdAt.wire(),
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ): Map<String, Any?>? {
        val end = (data["plannedTo"] ?: data["plannedFrom"]) as String?
        val finished = data["status"] in FINISHED
        if (finished && end != null && Instant.parse(end).isBefore(clock.instant().minus(WINDOW))) return null
        if (viewer.isOffice) return data
        val mine = viewer.userId.toString()

        @Suppress("UNCHECKED_CAST")
        val assignees = data["assigneeIds"] as List<String>
        return data.takeIf { mine in assignees || data["createdBy"] == mine }
    }

    /** Technicians change status and work notes of existing work orders; planning is the office's. */
    override fun mayWrite(
        field: String,
        viewer: SyncViewer,
    ): Boolean = viewer.isOffice || field == "status" || field == "workNotes"

    override fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val assignees =
            (values["assigneeIds"] as? List<*>)?.map { it.uuid("assigneeIds")!! }.orEmpty().ifEmpty { listOf(viewer.userId) }
        val change =
            WorkOrderChange(
                customerId = values["customerId"].uuid("customerId") ?: throw SyncRejection("CUSTOMER_REQUIRED"),
                siteId = values["siteId"].uuid("siteId"),
                title = values["title"].text("title")?.trim()?.ifEmpty { null } ?: throw SyncRejection("TITLE_REQUIRED"),
                description = values["description"].text("description")?.trim()?.ifEmpty { null },
                plannedFrom = values["plannedFrom"].instant("plannedFrom"),
                plannedTo = values["plannedTo"].instant("plannedTo"),
                assigneeIds = if (viewer.isOffice) assignees else (assignees + viewer.userId).distinct(),
                checklistTemplateId = values["checklistTemplateId"].uuid("checklistTemplateId"),
                status = values["status"].text("status")?.let(::status),
            )
        service.create(viewer.workspaceId, viewer.userId, id, change)
        values["workNotes"].text("workNotes")?.let { service.setWorkNotes(viewer.workspaceId, viewer.userId, id, it) }
        if (!viewer.isOffice) notifyOffice(id, change, viewer)
    }

    override fun update(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val current = orders.find(id) ?: throw SyncRejection("NOT_FOUND")
        val planning = values.keys - setOf("workNotes")
        if (planning.isNotEmpty()) {
            val change =
                WorkOrderChange(
                    customerId = if ("customerId" in values) values["customerId"].uuid("customerId")!! else current.customerId,
                    siteId = if ("siteId" in values) values["siteId"].uuid("siteId") else current.siteId,
                    title =
                        if ("title" in
                            values
                        ) {
                            values["title"].text("title")?.trim()?.ifEmpty { null } ?: throw SyncRejection("TITLE_REQUIRED")
                        } else {
                            current.title
                        },
                    description = if ("description" in values) values["description"].text("description") else current.description,
                    plannedFrom = if ("plannedFrom" in values) values["plannedFrom"].instant("plannedFrom") else current.plannedFrom,
                    plannedTo = if ("plannedTo" in values) values["plannedTo"].instant("plannedTo") else current.plannedTo,
                    assigneeIds =
                        if ("assigneeIds" in
                            values
                        ) {
                            (values["assigneeIds"] as? List<*>)?.map { it.uuid("assigneeIds")!! }.orEmpty()
                        } else {
                            current.assigneeIds
                        },
                    checklistTemplateId =
                        if ("checklistTemplateId" in
                            values
                        ) {
                            values["checklistTemplateId"].uuid("checklistTemplateId")
                        } else {
                            current.checklistTemplateId
                        },
                    status =
                        if ("status" in
                            values
                        ) {
                            status(values["status"].text("status") ?: throw SyncRejection("INVALID_STATUS"))
                        } else {
                            null
                        },
                )
            service.update(viewer.workspaceId, viewer.userId, id, change, current.version)
        }
        if ("workNotes" in values) service.setWorkNotes(viewer.workspaceId, viewer.userId, id, values["workNotes"].text("workNotes"))
    }

    private fun status(value: String): WorkOrderStatus =
        runCatching { WorkOrderStatus.valueOf(value) }.getOrNull() ?: throw SyncRejection("INVALID_STATUS")

    /** "Office notified after sync" (M-MO-02): owners and members get an email. */
    private fun notifyOffice(
        id: UUID,
        change: WorkOrderChange,
        viewer: SyncViewer,
    ) {
        val author = people.displayNames(listOf(viewer.userId))[viewer.userId] ?: ""
        val customer = customers.find(change.customerId)?.name ?: ""
        val link = "${mail.webBaseUrl}/work-orders/$id"
        for (to in people.officeRecipients()) {
            val locale = Locale.forLanguageTag(to.locale)
            outbox.send(
                Mail(
                    to = to.email,
                    subject = messages.getMessage("mail.workorder.fromapp.subject", arrayOf(change.title), locale),
                    body =
                        messages.getMessage(
                            "mail.workorder.fromapp.body",
                            arrayOf(change.title, to.displayName, author, customer, link),
                            locale,
                        ),
                ),
            )
        }
    }

    companion object {
        const val NAME = "work_order"
        private val FINISHED = setOf("DONE", "CANCELLED", "SIGNED", "INVOICED")
        private val WINDOW = java.time.Duration.ofDays(30)
    }
}

/** Job sites, read-only on the phone; the whole workspace. */
@Component
internal class SiteSyncEntity(
    private val sites: SiteRepository,
    private val jdbc: JdbcClient,
) : SyncEntity {
    override val name = "site"
    override val fields = emptyMap<String, FieldPolicy>()

    override fun allIds(): List<UUID> =
        jdbc
            .sql(
                "SELECT id FROM site WHERE workspace_id = current_workspace_id() ORDER BY id",
            ).query(UUID::class.java)
            .list()
            .filterNotNull()

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        sites.findAll(ids).associate {
            it.id to
                linkedMapOf(
                    "customerId" to it.customerId.toString(),
                    "name" to it.name,
                    "street" to it.street,
                    "postalCode" to it.postalCode,
                    "city" to it.city,
                    "countryCode" to it.countryCode,
                    "notes" to it.notes,
                    "archived" to it.archived,
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ) = data
}

/** Checklist templates, read-only on the phone (used from Stage 5). */
@Component
internal class ChecklistTemplateSyncEntity(
    private val templates: ChecklistTemplateRepository,
) : SyncEntity {
    override val name = "checklist_template"
    override val fields = emptyMap<String, FieldPolicy>()

    override fun allIds(): List<UUID> = templates.list(includeArchived = true).map { it.id }

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> {
        val wanted = ids.toSet()
        return templates.list(includeArchived = true).filter { it.id in wanted }.associate { t ->
            t.id to
                linkedMapOf(
                    "name" to t.name,
                    "items" to
                        t.items.map {
                            linkedMapOf("key" to it.key, "label" to it.label, "kind" to it.kind.name, "required" to it.required)
                        },
                    "archived" to t.archived,
                )
        }
    }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ) = data
}

/** Staff levels; the hourly rate only for users who may see prices (ADR 0039). */
@Component
internal class StaffLevelSyncEntity(
    private val levels: StaffLevelRepository,
) : SyncEntity {
    override val name = "staff_level"
    override val fields = emptyMap<String, FieldPolicy>()

    override fun allIds(): List<UUID> = levels.list(includeArchived = true).map { it.id }

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> {
        val wanted = ids.toSet()
        return levels.list(includeArchived = true).filter { it.id in wanted }.associate {
            it.id to linkedMapOf("name" to it.name, "hourlyRate" to money(it.hourlyRate).toPlainString(), "archived" to it.archived)
        }
    }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ): Map<String, Any?> = if (viewer.canSeePrices) data else data + ("hourlyRate" to null)
}

/** Prices on the phone: always for the office, for technicians only when the owner allowed it. */
@Component
internal class StaffPriceVisibility(
    private val profiles: StaffProfileRepository,
) : PriceVisibility {
    override fun canSeePrices(
        userId: UUID,
        role: WorkspaceRole,
    ): Boolean = role != WorkspaceRole.TECHNICIAN || profiles.all()[userId]?.canSeePrices == true
}

/**
 * Every audited change of this module goes into the change feed (one feed for office and app
 * edits, ADR 0038). A changed price visibility resends prices and rates.
 */
@Component
internal class WorkOrdersFeed(
    private val feed: ChangeFeed,
) {
    @EventListener
    fun on(event: WorkOrdersChanged) {
        when (event.kind) {
            "work_order" -> {
                feed.changed(WorkOrderSyncEntity.NAME, event.id)
            }

            "site", "staff_level", "checklist_template" -> {
                feed.changed(event.kind, event.id)
            }

            "staff" -> {
                feed.touchAll("staff_level")
                feed.touchAll("article")
            }
        }
    }
}
