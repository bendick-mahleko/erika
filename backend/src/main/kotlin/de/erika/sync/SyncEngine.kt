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
        val results =
            mutations.map { m ->
                repo.appliedResult(m.mutationId)?.let { return@map json.readValue(it, MutationResult::class.java) }
                val result =
                    try {
                        checkNotNull(savepoint.execute { apply(m, viewer) })
                    } catch (e: SyncRejection) {
                        rejected(m, e.code)
                    } catch (e: ErikaException) {
                        rejected(m, e.code)
                    } catch (e: DataIntegrityViolationException) {
                        rejected(m, "INVALID_DATA")
                    }
                repo.recordApplied(m.mutationId, viewer.deviceId, json.writeValueAsString(result))
                result
            }
        devices.recordSync(viewer.deviceId)
        return results
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
                val handler = entities.getValue(entity)
                handler.load(changes.map { it.id }).mapValues { (_, data) -> handler.view(data, viewer) }
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

    private fun apply(
        m: Mutation,
        viewer: SyncViewer,
    ): MutationResult {
        val entity = feed.entities[m.entity] ?: throw SyncRejection("UNKNOWN_ENTITY")
        val values: Map<String, Any?> = m.propertyValues
        values.keys.firstOrNull { it !in entity.fields }?.let { throw SyncRejection("FIELD_NOT_ALLOWED") }
        val existing = entity.load(listOf(m.entityId))[m.entityId]
        return when (m.op) {
            MutationOp.CREATE -> {
                if (existing == null) entity.create(m.entityId, values, viewer)
                // An existing record is answered with its current state: the create already happened.
                MutationResult(m.mutationId, MutationStatus.APPLIED, current(entity, m.entityId, viewer))
            }

            MutationOp.UPDATE -> {
                update(entity, m, values, existing ?: throw SyncRejection("NOT_FOUND"), viewer)
            }
        }
    }

    private fun update(
        entity: SyncEntity,
        m: Mutation,
        values: Map<String, Any?>,
        existing: Map<String, Any?>,
        viewer: SyncViewer,
    ): MutationResult {
        entity.view(existing, viewer) ?: throw SyncRejection("NOT_IN_SCOPE")
        values.keys.firstOrNull { !entity.mayWrite(it, viewer) }?.let { throw SyncRejection("FIELD_NOT_ALLOWED") }
        val states = repo.fields(entity.name, listOf(m.entityId))[m.entityId].orEmpty()
        val base = m.baseVersions.orEmpty()
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
                    repo.insertConflict(
                        ConflictNote(
                            id = UUID.randomUUID(),
                            entity = entity.name,
                            entityId = m.entityId,
                            field = field,
                            serverValue = json.writeValueAsString(existing[field]),
                            deviceValue = json.writeValueAsString(value),
                            winner = winner.name,
                            userId = viewer.userId,
                            deviceId = viewer.deviceId,
                        ),
                    )
                }
            }
        }
        if (write.isNotEmpty()) entity.update(m.entityId, write, viewer)
        val status = if (conflicts.isEmpty()) MutationStatus.APPLIED else MutationStatus.CONFLICT
        return MutationResult(m.mutationId, status, current(entity, m.entityId, viewer), conflicts.ifEmpty { null })
    }

    /** The record as the phone should now hold it; null when it left the user's scope. */
    private fun current(
        entity: SyncEntity,
        id: UUID,
        viewer: SyncViewer,
    ): SyncRecord? {
        val data = entity.load(listOf(id))[id]?.let { entity.view(it, viewer) } ?: return null
        val row = repo.row(entity.name, id) ?: return null
        return record(row, data)
    }

    private fun record(
        row: ChangeRow,
        data: Map<String, Any?>,
    ): SyncRecord {
        @Suppress("UNCHECKED_CAST")
        val values = data as Map<String, Any>
        return SyncRecord(row.entity, row.id, row.seq, row.fields.mapValues { it.value.version }, values)
    }

    private fun rejected(
        m: Mutation,
        code: String,
    ) = MutationResult(m.mutationId, MutationStatus.REJECTED, code = code)

    companion object {
        const val PULL_LIMIT = 500

        fun checkProtocol(version: Int) {
            if (version < SyncProtocol.MINIMUM || version > SyncProtocol.CURRENT) {
                throw ErikaException("PROTOCOL_UNSUPPORTED", HttpStatus.UPGRADE_REQUIRED, "Please update the MARA app.")
            }
        }
    }
}
