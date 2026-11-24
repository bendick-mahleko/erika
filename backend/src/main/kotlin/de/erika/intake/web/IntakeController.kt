package de.erika.intake.web

import de.erika.contract.api.IntakeApi
import de.erika.contract.model.IntakeAddress
import de.erika.contract.model.IntakeItem
import de.erika.contract.model.IntakeSender
import de.erika.contract.model.IntakeSenderRequest
import de.erika.contract.model.IntakeSettings
import de.erika.contract.model.IntakeSettingsUpdate
import de.erika.contract.model.QuarantineList
import de.erika.contract.model.QuarantineRelease
import de.erika.contract.model.QuarantinedMail
import de.erika.contract.model.Upload
import de.erika.contract.model.UploadRequest
import de.erika.contract.model.UploadStatus
import de.erika.intake.AllowList
import de.erika.intake.AllowListRepository
import de.erika.intake.IntakeAddresses
import de.erika.intake.IntakeRow
import de.erika.intake.ItemResult
import de.erika.intake.StartedUpload
import de.erika.intake.Uploads
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.Idempotency
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import de.erika.contract.model.IntakeOutcome as ApiOutcome
import de.erika.contract.model.IntakeReason as ApiReason

/**
 * Upload intake and the intake address. Runs in the tenant transaction; state-changing calls
 * are limited to OWNER and MEMBER by the security configuration (tax advisors only read).
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class IntakeController(
    private val uploads: Uploads,
    private val addresses: IntakeAddresses,
    private val allowList: AllowList,
    private val allowListRepository: AllowListRepository,
    private val idempotency: Idempotency,
) : IntakeApi {
    // --- IN-06: sender allow-list and quarantine ---------------------------------------

    override fun getIntakeSettings(): ResponseEntity<IntakeSettings> = ResponseEntity.ok(settings())

    @PreAuthorize("hasRole('OWNER')")
    override fun updateIntakeSettings(intakeSettingsUpdate: IntakeSettingsUpdate): ResponseEntity<IntakeSettings> {
        val p = ErikaPrincipal.current()
        allowList.setEnabled(p.workspaceId, p.userId, intakeSettingsUpdate.allowListEnabled)
        return ResponseEntity.ok(settings())
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun addIntakeSender(
        idempotencyKey: String,
        intakeSenderRequest: IntakeSenderRequest,
    ): ResponseEntity<IntakeSender> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "addIntakeSender") {
                allowList.addSender(p.workspaceId, p.userId, intakeSenderRequest.pattern)
            }
        val row = allowListRepository.senders().first { it.id == result.resourceId }
        return ResponseEntity.status(HttpStatus.CREATED).body(IntakeSender(row.id, row.pattern, row.createdAt))
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun removeIntakeSender(senderId: UUID): ResponseEntity<Unit> {
        val p = ErikaPrincipal.current()
        allowList.removeSender(p.workspaceId, p.userId, senderId)
        return ResponseEntity.noContent().build()
    }

    override fun listQuarantine(): ResponseEntity<QuarantineList> =
        ResponseEntity.ok(
            QuarantineList(
                allowListRepository.quarantined().map {
                    QuarantinedMail(
                        id = it.id,
                        receivedAt = it.receivedAt,
                        sender = it.sender,
                        fromAddress = it.fromAddress,
                        subject = it.subject,
                    )
                },
            ),
        )

    override fun releaseQuarantinedMail(
        intakeId: UUID,
        quarantineRelease: QuarantineRelease,
    ): ResponseEntity<Unit> {
        val p = ErikaPrincipal.current()
        allowList.release(p.workspaceId, p.userId, intakeId, quarantineRelease.allowSender ?: false)
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }

    override fun discardQuarantinedMail(intakeId: UUID): ResponseEntity<Unit> {
        val p = ErikaPrincipal.current()
        allowList.discard(p.workspaceId, p.userId, intakeId)
        return ResponseEntity.noContent().build()
    }

    private fun settings() =
        IntakeSettings(
            allowListEnabled = allowListRepository.enabled(),
            senders = allowListRepository.senders().map { IntakeSender(it.id, it.pattern, it.createdAt) },
        )

    override fun getIntakeAddress(): ResponseEntity<IntakeAddress> =
        ResponseEntity.ok(IntakeAddress(address = addresses.addressOf(ErikaPrincipal.current().workspaceId)))

    override fun createUpload(uploadRequest: UploadRequest): ResponseEntity<Upload> {
        val principal = ErikaPrincipal.current()
        val started =
            uploads.start(
                principal.workspaceId,
                principal.userId,
                uploadRequest.fileName,
                uploadRequest.propertySize,
                uploadRequest.contentType,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(started.toApi())
    }

    override fun completeUpload(uploadId: UUID): ResponseEntity<Upload> {
        val intake = uploads.complete(uploadId)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(intake.toApi(emptyList()))
    }

    override fun getUpload(uploadId: UUID): ResponseEntity<Upload> {
        val (intake, items) = uploads.find(uploadId)
        return ResponseEntity.ok(intake.toApi(items))
    }

    private fun StartedUpload.toApi() =
        intake.toApi(emptyList()).copy(
            uploadUrl = presigned.url,
            uploadHeaders = presigned.headers,
            uploadExpiresAt = OffsetDateTime.ofInstant(presigned.expiresAt, ZoneOffset.UTC),
        )

    private fun IntakeRow.toApi(items: List<ItemResult>) =
        Upload(
            id = id,
            fileName = fileName ?: "",
            propertySize = declaredSize ?: 0,
            status = UploadStatus.forValue(status.name),
            createdAt = createdAt,
            reason = reason?.let { ApiReason.forValue(it.name) },
            items =
                items.map {
                    IntakeItem(
                        fileName = it.fileName,
                        outcome = ApiOutcome.forValue(it.outcome.name),
                        reason = it.reason?.let { r -> ApiReason.forValue(r.name) },
                        documentId = it.documentId,
                    )
                },
        )
}
