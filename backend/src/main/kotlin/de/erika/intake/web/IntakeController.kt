package de.erika.intake.web

import de.erika.contract.api.IntakeApi
import de.erika.contract.model.IntakeAddress
import de.erika.contract.model.IntakeItem
import de.erika.contract.model.Upload
import de.erika.contract.model.UploadRequest
import de.erika.contract.model.UploadStatus
import de.erika.intake.IntakeAddresses
import de.erika.intake.IntakeRow
import de.erika.intake.ItemResult
import de.erika.intake.StartedUpload
import de.erika.intake.Uploads
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
) : IntakeApi {
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
