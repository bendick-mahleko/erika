package de.erika.sync.web

import de.erika.contract.api.SyncApi
import de.erika.contract.mobile.api.MobileSyncApi
import de.erika.contract.mobile.model.PullResult
import de.erika.contract.mobile.model.PushRequest
import de.erika.contract.mobile.model.PushResult
import de.erika.contract.model.SyncConflict
import de.erika.contract.model.SyncConflictList
import de.erika.identity.WorkspaceContacts
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.sync.SyncEngine
import de.erika.sync.SyncRepository
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** `POST /mobile/sync/push` and `GET /mobile/sync/pull` (api/mobile.yaml, ADR 0038). */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class MobileSyncController(
    private val engine: SyncEngine,
) : MobileSyncApi {
    override fun syncPush(pushRequest: PushRequest): ResponseEntity<PushResult> {
        SyncEngine.checkProtocol(pushRequest.protocolVersion)
        val viewer = engine.viewer(ErikaPrincipal.current())
        return ResponseEntity.ok(PushResult(engine.push(viewer, pushRequest.mutations)))
    }

    override fun syncPull(
        protocolVersion: Int,
        cursor: String?,
    ): ResponseEntity<PullResult> {
        SyncEngine.checkProtocol(protocolVersion)
        val viewer = engine.viewer(ErikaPrincipal.current())
        return ResponseEntity.ok(engine.pull(viewer, cursor?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0))
    }
}

/** `GET /sync-conflicts` (openapi.yaml): notes on concurrent changes, for the office. */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class SyncConflictsController(
    private val repo: SyncRepository,
    private val people: WorkspaceContacts,
) : SyncApi {
    override fun listSyncConflicts(entityId: UUID): ResponseEntity<SyncConflictList> {
        val notes = repo.conflicts(entityId)
        val names = people.displayNames(notes.map { it.userId })
        return ResponseEntity.ok(
            SyncConflictList(
                notes.map {
                    SyncConflict(
                        id = it.id,
                        entity = it.entity,
                        entityId = it.entityId,
                        field = it.field,
                        winner = SyncConflict.Winner.forValue(it.winner),
                        userId = it.userId,
                        displayName = names[it.userId] ?: "?",
                        createdAt = checkNotNull(it.createdAt),
                        serverValue = it.serverValue,
                        deviceValue = it.deviceValue,
                    )
                },
            ),
        )
    }
}
