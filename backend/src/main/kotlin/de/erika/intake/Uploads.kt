package de.erika.intake

import de.erika.documents.ReceivedVia
import de.erika.platform.jobs.JobContext
import de.erika.platform.jobs.JobHandler
import de.erika.platform.jobs.JobQueue
import de.erika.platform.storage.ObjectStorage
import de.erika.platform.storage.PresignedUpload
import de.erika.platform.storage.StorageProperties
import de.erika.platform.web.ErikaException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

internal data class StartedUpload(
    val intake: IntakeRow,
    val presigned: PresignedUpload,
)

internal object IntakeErrors {
    fun uploadNotFound() = ErikaException("UPLOAD_NOT_FOUND", HttpStatus.NOT_FOUND, "Upload not found.")

    fun fileNotReceived() =
        ErikaException("UPLOAD_FILE_MISSING", HttpStatus.CONFLICT, "The file has not arrived yet. Send it to the upload URL first.")

    fun sizeMismatch() =
        ErikaException("UPLOAD_SIZE_MISMATCH", HttpStatus.CONFLICT, "The file that arrived doesn't have the announced size.")
}

/**
 * Upload intake (IN-02): the browser announces a file, sends it straight to object storage
 * with a pre-signed URL (the API never holds the bytes), then confirms. The file waits in
 * the uploads bucket (quarantine) until the worker has scanned and imported it.
 */
@Service
internal class Uploads(
    private val repository: IntakeRepository,
    private val storage: ObjectStorage,
    private val jobs: JobQueue,
    private val props: IntakeProperties,
    private val storageProps: StorageProperties,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun start(
        workspaceId: UUID,
        userId: UUID,
        fileName: String,
        size: Long,
        contentType: String,
    ): StartedUpload {
        require(size in 1..props.maxFileBytes) { "size out of range" }
        val id = UUID.randomUUID()
        val key = "ws/$workspaceId/$id"
        val type = contentType.trim().ifEmpty { "application/octet-stream" }
        val name = IntakePipeline.displayName(fileName)
        val presigned = storage.presignUpload(key, type, size, props.uploadUrlValidity)
        repository.insertUpload(id, workspaceId, name, size, type, key, userId)
        return StartedUpload(repository.find(id)!!, presigned)
    }

    /** Confirms the file arrived and queues it. Repeating it for a queued or finished upload changes nothing. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun complete(uploadId: UUID): IntakeRow {
        val intake = repository.find(uploadId)?.takeIf { it.source == IntakeSource.UPLOAD } ?: throw IntakeErrors.uploadNotFound()
        if (intake.status != IntakeStatus.AWAITING_FILE) return intake
        val size = storage.size(storageProps.uploadsBucket, intake.uploadKey!!) ?: throw IntakeErrors.fileNotReceived()
        if (size != intake.declaredSize) throw IntakeErrors.sizeMismatch()
        if (repository.markReceived(intake.id)) {
            jobs.enqueue(ProcessUploadJobHandler.TYPE, ProcessUploadJobHandler.Payload(intake.id), workspaceId = intake.workspaceId)
        }
        return repository.find(uploadId)!!
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun find(uploadId: UUID): Pair<IntakeRow, List<ItemResult>> {
        val intake = repository.find(uploadId)?.takeIf { it.source == IntakeSource.UPLOAD } ?: throw IntakeErrors.uploadNotFound()
        return intake to repository.items(intake.id)
    }
}

/**
 * Worker: scans, unpacks and imports one uploaded file, then deletes it from the uploads
 * bucket after the transaction commits. Idempotent: an upload that is no longer RECEIVED
 * has been processed; a retry after a crash starts over with nothing recorded.
 */
@Component
internal class ProcessUploadJobHandler(
    private val repository: IntakeRepository,
    private val pipeline: IntakePipeline,
    private val storage: ObjectStorage,
    private val storageProps: StorageProperties,
) : JobHandler<ProcessUploadJobHandler.Payload> {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Payload(
        val intakeId: UUID,
    )

    override val type = TYPE
    override val payloadType = Payload::class.java

    override fun handle(
        payload: Payload,
        context: JobContext,
    ) {
        val intake = repository.find(payload.intakeId) ?: return
        if (intake.status != IntakeStatus.RECEIVED) return
        val key = intake.uploadKey!!
        val bytes = storage.get(storageProps.uploadsBucket, key)
        if (bytes == null) {
            log.warn("Upload {}: file missing from the uploads bucket", intake.id)
            pipeline.complete(intake, emptyList(), IntakeStatus.REJECTED, IntakeReason.FILE_MISSING)
            return
        }
        val items = pipeline.importFiles(intake, listOf(IncomingFile(intake.fileName ?: "upload", bytes)), ReceivedVia.UPLOAD)
        // One uploaded file: a refused ZIP (bomb, too deep …) refuses the whole upload.
        val zipRefusal = items.singleOrNull()?.takeIf { it.outcome == IntakeOutcome.REJECTED && it.reason?.zipLevel == true }?.reason
        pipeline.complete(intake, items, if (zipRefusal != null) IntakeStatus.REJECTED else IntakeStatus.DONE, zipRefusal)
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    runCatching { storage.delete(storageProps.uploadsBucket, key) }
                        .onFailure {
                            log.warn(
                                "Upload {}: could not delete the quarantined file; the bucket lifecycle rule will",
                                intake.id,
                                it,
                            )
                        }
                }
            },
        )
    }

    companion object {
        const val TYPE = "intake.process-upload"
    }
}
