package de.erika.documents

import de.erika.archive.StoredObject
import de.erika.platform.audit.AuditableEvent
import de.erika.platform.jobs.JobQueue
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** How a document reached ERIKA (`document.received_via`). */
enum class ReceivedVia { UPLOAD, EMAIL, CREATED }

/**
 * Result of [Documents.registerIncoming]. [created] is false when the workspace already has
 * a document with the same bytes: then [documentId] is that document (ADR 0023).
 */
data class DocumentRegistration(
    val documentId: UUID,
    val created: Boolean,
)

data class DocumentReceived(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val documentId: UUID,
    val receivedVia: ReceivedVia,
    val sha256: String,
) : AuditableEvent {
    override val action = "document.received"
    override val details get() = mapOf("documentId" to documentId.toString(), "via" to receivedVia.name, "sha256" to sha256)
}

data class DocumentValidated(
    override val workspaceId: UUID,
    val documentId: UUID,
    val format: String,
    val level: String,
    val packId: String,
    val duplicateOf: UUID?,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "document.validated"
    override val details
        get() =
            buildMap {
                put("documentId", documentId.toString())
                put("format", format)
                put("level", level)
                put("packId", packId)
                duplicateOf?.let { put("duplicateOf", it.toString()) }
            }
}

/**
 * Published API of the `documents` module: registers received originals as documents.
 * Reading and validating happens in the background ([ParseAndValidateJobHandler]).
 */
@Service
class Documents internal constructor(
    private val repository: DocumentRepository,
    private val jobs: JobQueue,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Creates a document (status NEW) for an archived original and queues reading and
     * validation, in the caller's transaction (Software Design §5, Flow A step 4). The same
     * bytes are one document per workspace: a second call returns the existing document.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun registerIncoming(
        stored: StoredObject,
        fileName: String?,
        via: ReceivedVia,
        actorId: UUID?,
    ): DocumentRegistration {
        repository.findIdBySha256(stored.workspaceId, stored.sha256)?.let { return DocumentRegistration(it, created = false) }
        val id = UUID.randomUUID()
        val inserted = repository.insertIncoming(id, stored, fileName, via)
        if (!inserted) {
            // A concurrent intake of the same bytes won; its document stands.
            return DocumentRegistration(repository.findIdBySha256(stored.workspaceId, stored.sha256)!!, created = false)
        }
        jobs.enqueue(
            ParseAndValidateJobHandler.TYPE,
            ParseAndValidateJobHandler.Payload(id),
            workspaceId = stored.workspaceId,
            maxAttempts = ParseAndValidateJobHandler.MAX_ATTEMPTS,
        )
        events.publishEvent(DocumentReceived(stored.workspaceId, actorId, id, via, stored.sha256))
        return DocumentRegistration(id, created = true)
    }
}
