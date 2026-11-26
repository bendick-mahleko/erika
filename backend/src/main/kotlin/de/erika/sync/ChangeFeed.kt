package de.erika.sync

import de.erika.platform.jobs.JobQueue
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * Published API of the `sync` module: the one change feed for office and app edits (ADR 0038).
 * Modules call [changed] in the transaction that changed a syncable record. The feed gives the
 * record the next sequence number of the workspace and a new version to every field whose
 * value changed (compared by hash; no values are stored). Runs in the caller's transaction,
 * scoped to the workspace.
 */
@Service
class ChangeFeed internal constructor(
    private val repo: SyncRepository,
    private val entityBeans: ObjectProvider<SyncEntity>,
    private val jobs: ObjectProvider<JobQueue>,
    private val json: JsonMapper,
) {
    internal val entities: Map<String, SyncEntity> by lazy {
        entityBeans.orderedStream().toList().associateBy { it.name }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun changed(
        entity: String,
        id: UUID,
    ) = changedAll(entity, listOf(id))

    @Transactional(propagation = Propagation.MANDATORY)
    fun changedAll(
        entity: String,
        ids: Collection<UUID>,
    ) {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return
        val handler = entities[entity] ?: error("No SyncEntity named $entity")
        var seq = repo.reserve(distinct.size)
        val data = handler.load(distinct)
        val before = repo.fields(entity, distinct)
        for (id in distinct) {
            val old = before[id].orEmpty()
            val current = data[id].orEmpty()
            val fields =
                current.mapValues { (field, value) ->
                    val hash = hash(value)
                    old[field]?.takeIf { it.hash == hash } ?: FieldState(seq, hash)
                }
            repo.upsert(entity, id, seq, fields)
            seq++
        }
        notifyDevices()
    }

    /**
     * Sends every record of [entity] again, e.g. when a user's price visibility changed and the
     * phones must receive the records with or without prices.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun touchAll(entity: String) {
        val count = repo.count(entity)
        if (count == 0) return
        repo.touchAll(entity, repo.reserve(count))
        notifyDevices()
    }

    /** The first sync of a workspace enters every existing record once. */
    @Transactional(propagation = Propagation.MANDATORY)
    internal fun bootstrapIfNeeded() {
        if (repo.lockBootstrapped()) return
        entities.values.sortedBy { it.name }.forEach { changedAll(it.name, it.allIds()) }
        repo.markBootstrapped()
    }

    internal fun hash(value: Any?): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)))

    /** One "sync now" job per transaction, run shortly after commit (ADR 0038). */
    private fun notifyDevices() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        if (TransactionSynchronizationManager.hasResource(NOTIFY_KEY)) return
        TransactionSynchronizationManager.bindResource(NOTIFY_KEY, true)
        TransactionSynchronizationManager.registerSynchronization(
            object : org.springframework.transaction.support.TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(NOTIFY_KEY)
                }
            },
        )
        val queue = jobs.ifAvailable ?: return
        val workspaceId = currentWorkspace() ?: return
        queue.enqueue(NotifyDevicesJob.TYPE, NotifyDevices(workspaceId), workspaceId, java.time.Duration.ofSeconds(2), maxAttempts = 3)
    }

    private fun currentWorkspace(): UUID? = repo.currentWorkspace()

    private companion object {
        val NOTIFY_KEY = Any()
    }
}
