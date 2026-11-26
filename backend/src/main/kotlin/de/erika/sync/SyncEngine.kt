package de.erika.sync

import de.erika.contract.mobile.model.ConflictWinner
import de.erika.contract.mobile.model.FieldConflict
import de.erika.contract.mobile.model.Mutation
import de.erika.contract.mobile.model.MutationOp
import de.erika.contract.mobile.model.MutationResult
import de.erika.contract.mobile.model.MutationStatus
import de.erika.contract.mobile.model.PullResult
import de.erika.contract.mobile.model.SyncRecord
import de.erika.contract.mobile.model.SyncRemoval
import de.erika.identity.MobileDevices
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.ErikaException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Push and pull (ADR 0038). Runs in the request's tenant transaction; each pushed mutation gets
 * a savepoint of its own, so a rejected one leaves no trace while the others commit.
 */
@Service
@Profile(RuntimeInstance.PROFILE_API)
internal class SyncEngine(
    private val feed: ChangeFeed,
    private val repo: SyncRepository,
    private val devices: MobileDevices,
    private val prices: ObjectProvider<PriceVisibility>,
    private val json: JsonMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val savepoint =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_NESTED }

    fun viewer(principal: ErikaPrincipal): SyncViewer {
        val deviceId = checkNotNull(principal.deviceId) { "sync without a device" }
        val visibility = prices.ifAvailable
        val canSee = visibility?.canSeePrices(principal.userId, principal.role) ?: (principal.role.name != "TECHNICIAN")
        return SyncViewer(principal.userId, principal.workspaceId, principal.role, deviceId, canSee)
    }

    fun push(
        viewer: SyncViewer,
        mutations: List<Mutation>,
    ): List<MutationResult> {
        feed.bootstrapIfNeeded()
        repo.forgetOldMutations(viewer.deviceId)
        // Field versions this push already answered (fresh or from the idempotency log). A later
        // mutation of the same record was made on top of them, even though the phone hasn't
        // received that answer yet: a create followed by an edit or a delete is no conflict.
        val seen = mutableMapOf<String, MutableMap<String, Long>>()
        val results =
            mutations.map { m ->
                val result =
                    repo.appliedResult(m.mutationId)?.let { json.readValue(it, MutationResult::class.java) }
                        ?: applyOnce(m, viewer, seen["${m.entity}/${m.entityId}"].orEmpty())
                result.record?.let { r ->
                    val known = seen.getOrPut("${r.entity}/${r.id}") { mutableMapOf() }
                    r.fieldVersions.forEach { (f, v) -> known.merge(f, v, ::maxOf) }
                }
                result
            }
        devices.recordSync(viewer.deviceId)
        return results
    }

    private fun applyOnce(
        m: Mutation,
        viewer: SyncViewer,
        seen: Map<String, Long>,
    ): MutationResult {
        val result =
            try {
                checkNotNull(savepoint.execute { apply(m, viewer, seen) })
            } catch (e: SyncRejection) {
                rejected(m, e.code)
            } catch (e: ErikaException) {
                rejected(m, e.code)
            } catch (e: DataIntegrityViolationException) {
                rejected(m, "INVALID_DATA")
            }
        repo.recordApplied(m.mutationId, viewer.deviceId, json.writeValueAsString(result))
        return result
    }

    fun pull(
        viewer: SyncViewer,
        cursor: Long,
    ): PullResult {
        feed.bootstrapIfNeeded()
        val entities = feed.entities
        val rows = repo.since(cursor, entities.keys, PULL_LIMIT + 1)
        val page = rows.take(PULL_LIMIT)
        val views =
            page.groupBy { it.entity }.mapValues { (entity, changes) ->
                visible(entities.getValue(entity), changes.map { it.id }, viewer)
            }
        val records = mutableListOf<SyncRecord>()
        val removals = mutableListOf<SyncRemoval>()
        for (row in page) {
            val data = views[row.entity]?.get(row.id)
            if (data == null) removals += SyncRemoval(row.entity, row.id) else records += record(row, data)
        }
        devices.recordSync(viewer.deviceId)
        return PullResult(
            records = records,
            removals = removals,
            nextCursor = (page.lastOrNull()?.seq ?: cursor).toString(),
            hasMore = rows.size > PULL_LIMIT,
        )
    }

    /** What [viewer] sees of [ids]; deleted records and records outside the scope are missing. */
    private fun visible(
        entity: SyncEntity,
        ids: Collection<UUID>,
        viewer: SyncViewer,
    ): Map<UUID, Map<String, Any?>> {
        val live = entity.load(ids).filterValues { it[SyncEntity.DELETED] != true }
        if (live.isEmpty()) return emptyMap()
        return entity
            .viewAll(live, viewer)
            .mapNotNull { (id, data) -> data?.let { id to it } }
            .toMap()
    }

    private fun inScope(
        entity: SyncEntity,
        id: UUID,
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        entity.viewAll(mapOf(id to data), viewer)[id] ?: throw SyncRejection("NOT_IN_SCOPE")
    }

    private fun apply(
        m: Mutation,
        viewer: SyncViewer,
        seen: Map<String, Long>,
    ): MutationResult {
        val entity = feed.entities[m.entity] ?: throw SyncRejection("UNKNOWN_ENTITY")
        val values: Map<String, Any?> = m.propertyValues
        values.keys.firstOrNull { it !in entity.fields }?.let { throw SyncRejection("FIELD_NOT_ALLOWED") }
        val sent = m.baseVersions.orEmpty()
        val base = (sent.keys + seen.keys).associateWith { maxOf(sent[it] ?: 0, seen[it] ?: 0) }
        val existing = entity.load(listOf(m.entityId))[m.entityId]
        val deleted = existing?.get(SyncEntity.DELETED) == true
        return when (m.op) {
            MutationOp.CREATE -> {
                if (existing == null) {
                    entity.create(m.entityId, values, viewer)
                    MutationResult(m.mutationId, MutationStatus.APPLIED, current(entity, m.entityId, viewer))
                } else {
                    // Already there: a resend after the idempotency log forgot it, or an ID two
                    // phones derived alike (a report, a checklist answer). Merged like an edit
                    // made without seeing the record; fields only settable on create are dropped.
                    val editable = values.filterKeys { entity.mayWrite(it, viewer) }
                    if (deleted) {
                        restore(entity, m, existing, base, viewer, editable)
                    } else {
                        update(entity, m, editable, existing, base, viewer)
                    }
                }
            }

            MutationOp.UPDATE -> {
                existing ?: throw SyncRejection("NOT_FOUND")
                if (deleted) restore(entity, m, existing, base, viewer, values) else update(entity, m, values, existing, base, viewer)
            }

            MutationOp.DELETE -> {
                if (existing == null || deleted) {
                    MutationResult(m.mutationId, MutationStatus.APPLIED)
                } else {
                    delete(entity, m, existing, base, viewer)
                }
            }
        }
    }

    private fun delete(
        entity: SyncEntity,
        m: Mutation,
        existing: Map<String, Any?>,
        base: Map<String, Long>,
        viewer: SyncViewer,
    ): MutationResult {
        inScope(entity, m.entityId, existing, viewer)
        val states = repo.fields(entity.name, listOf(m.entityId))[m.entityId].orEmpty()
        val server = states.filterKeys { it != SyncEntity.DELETED }.mapValues { it.value.version }
        return when (ConflictRules.decideDelete(base, server)) {
            DeleteDecision.DELETE -> {
                entity.delete(m.entityId, viewer)
                MutationResult(m.mutationId, MutationStatus.APPLIED)
            }

            DeleteDecision.KEEP -> {
                note(entity, m, DELETED_FIELD, ConflictWinner.SERVER, false, true, viewer)
                MutationResult(
                    m.mutationId,
                    MutationStatus.CONFLICT,
                    current(entity, m.entityId, viewer),
                    listOf(FieldConflict(DELETED_FIELD, ConflictWinner.SERVER, false, true)),
                )
            }
        }
    }

    /** An edit of a deleted record: it wins when the delete happened after the phone's last view. */
    private fun restore(
        entity: SyncEntity,
        m: Mutation,
        existing: Map<String, Any?>,
        base: Map<String, Long>,
        viewer: SyncViewer,
        values: Map<String, Any?>,
    ): MutationResult {
        val states = repo.fields(entity.name, listOf(m.entityId))[m.entityId].orEmpty()
        val deletedVersion = states[SyncEntity.DELETED]?.version ?: 0
        if (ConflictRules.decideEditOfDeleted(base.values.maxOrNull() ?: 0, deletedVersion) == EditDeletedDecision.REJECT) {
            throw SyncRejection("NOT_FOUND")
        }
        inScope(entity, m.entityId, existing - SyncEntity.DELETED, viewer)
        entity.restore(m.entityId, viewer)
        note(entity, m, DELETED_FIELD, ConflictWinner.DEVICE, true, false, viewer)
        val restored = entity.load(listOf(m.entityId))[m.entityId] ?: throw SyncRejection("NOT_FOUND")
        val result = update(entity, m, values, restored, base, viewer)
        return result.copy(
            status = MutationStatus.CONFLICT,
            conflicts = listOf(FieldConflict(DELETED_FIELD, ConflictWinner.DEVICE, true, false)) + result.conflicts.orEmpty(),
        )
    }

    private fun update(
        entity: SyncEntity,
        m: Mutation,
        values: Map<String, Any?>,
        existing: Map<String, Any?>,
        base: Map<String, Long>,
        viewer: SyncViewer,
    ): MutationResult {
        inScope(entity, m.entityId, existing, viewer)
        values.keys.firstOrNull { !entity.mayWrite(it, viewer) }?.let { throw SyncRejection("FIELD_NOT_ALLOWED") }
        val states = repo.fields(entity.name, listOf(m.entityId))[m.entityId].orEmpty()
        val write = linkedMapOf<String, Any?>()
        val conflicts = mutableListOf<FieldConflict>()
        for ((field, value) in values) {
            val decision =
                ConflictRules.decide(
                    policy = entity.fields.getValue(field),
                    baseVersion = base[field] ?: 0,
                    serverVersion = states[field]?.version ?: 0,
                    sameValue = feed.hash(value) == feed.hash(existing[field]),
                )
            when (decision) {
                FieldDecision.APPLY -> {
                    write[field] = value
                }

                FieldDecision.SAME -> {}

                FieldDecision.KEEP_SERVER, FieldDecision.APPLY_OVER -> {
                    val winner = if (decision == FieldDecision.KEEP_SERVER) ConflictWinner.SERVER else ConflictWinner.DEVICE
                    if (winner == ConflictWinner.DEVICE) write[field] = value
                    conflicts += FieldConflict(field, winner, existing[field], value)
                    note(entity, m, field, winner, existing[field], value, viewer)
                }
            }
        }
        if (write.isNotEmpty()) entity.update(m.entityId, write, viewer)
        val status = if (conflicts.isEmpty()) MutationStatus.APPLIED else MutationStatus.CONFLICT
        return MutationResult(m.mutationId, status, current(entity, m.entityId, viewer), conflicts.ifEmpty { null })
    }

    private fun note(
        entity: SyncEntity,
        m: Mutation,
        field: String,
        winner: ConflictWinner,
        serverValue: Any?,
        deviceValue: Any?,
        viewer: SyncViewer,
    ) = repo.insertConflict(
        ConflictNote(
            id = UUID.randomUUID(),
            entity = entity.name,
            entityId = m.entityId,
            field = field,
            serverValue = json.writeValueAsString(serverValue),
            deviceValue = json.writeValueAsString(deviceValue),
            winner = winner.name,
            userId = viewer.userId,
            deviceId = viewer.deviceId,
        ),
    )

    /** The record as the phone should now hold it; null when it left the user's scope. */
    private fun current(
        entity: SyncEntity,
        id: UUID,
        viewer: SyncViewer,
    ): SyncRecord? {
        val data = visible(entity, listOf(id), viewer)[id] ?: return null
        val row = repo.row(entity.name, id) ?: return null
        return record(row, data)
    }

    private fun record(
        row: ChangeRow,
        data: Map<String, Any?>,
    ): SyncRecord {
        @Suppress("UNCHECKED_CAST")
        val values = (data - SyncEntity.DELETED) as Map<String, Any>
        val versions = row.fields.filterKeys { it != SyncEntity.DELETED }.mapValues { it.value.version }
        return SyncRecord(row.entity, row.id, row.seq, versions, values)
    }

    private fun rejected(
        m: Mutation,
        code: String,
    ) = MutationResult(m.mutationId, MutationStatus.REJECTED, code = code)

    companion object {
        const val PULL_LIMIT = 500

        /** The field name of delete conflicts in results and in the office's notes. */
        const val DELETED_FIELD = "deleted"

        fun checkProtocol(version: Int) {
            if (version < SyncProtocol.MINIMUM || version > SyncProtocol.CURRENT) {
                throw ErikaException("PROTOCOL_UNSUPPORTED", HttpStatus.UPGRADE_REQUIRED, "Please update the MARA app.")
            }
        }
    }
}
