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
 * Processing status. Incoming invoices (AR-03): NEW … BOOKED. Outgoing invoices (build plan,
 * gap 4): ISSUED, SENT, DELIVERY_FAILED, PAID, CANCELLED, BOOKED.
 */
enum class DocumentStatus {
    NEW,
    CHECKED,
    APPROVED,
    PAID,
    DISPUTED,
    BOOKED,
    ISSUED,
    SENT,
    DELIVERY_FAILED,
    CANCELLED,
    ;

    companion object {
        /** Statuses a user may set on a document of this direction (the others are set by ERIKA). */
        fun settableBy(direction: String): Set<DocumentStatus> =
            if (direction == "OUT") setOf(SENT, PAID, BOOKED) else setOf(NEW, CHECKED, APPROVED, PAID, DISPUTED, BOOKED)
    }
}

/** An invoice created in ERIKA was issued and archived as an outgoing document (CR-03). */
data class DocumentIssued(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val documentId: UUID,
    val sha256: String,
) : AuditableEvent {
    override val action = "document.issued"
    override val details get() = mapOf("documentId" to documentId.toString(), "sha256" to sha256)
}

data class DocumentStatusChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val documentId: UUID,
    val from: String,
    val to: String,
) : AuditableEvent {
    override val action = "document.status.changed"
    override val details get() = mapOf("documentId" to documentId.toString(), "from" to from, "to" to to)
}

/** Tags or note changed; the values themselves are not audited (they may contain invoice content). */
data class DocumentMetadataChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val documentId: UUID,
    val fields: List<String>,
) : AuditableEvent {
    override val action = "document.metadata.changed"
    override val details get() = mapOf("documentId" to documentId.toString(), "fields" to fields.sorted().joinToString(","))
}

data class RevalidationRequested(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val documentId: UUID,
) : AuditableEvent {
    override val action = "document.revalidation.requested"
    override val details get() = mapOf("documentId" to documentId.toString())
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

    /**
     * Registers an invoice issued by the creator (direction OUT, status ISSUED) for its archived
     * file, and queues reading it for the viewer and search, in the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun registerOutgoing(
        stored: StoredObject,
        fileName: String,
        actorId: UUID?,
    ): UUID {
        val id = UUID.randomUUID()
        repository.insertOutgoing(id, stored, fileName)
        repository.insertStatusEvent(stored.workspaceId, id, null, DocumentStatus.ISSUED.name, null, actorId)
        jobs.enqueue(
            ParseAndValidateJobHandler.TYPE,
            ParseAndValidateJobHandler.Payload(id),
            workspaceId = stored.workspaceId,
            maxAttempts = ParseAndValidateJobHandler.MAX_ATTEMPTS,
        )
        events.publishEvent(DocumentIssued(stored.workspaceId, actorId, id, stored.sha256))
        return id
    }

    /** Current status of each of the given documents of the workspace (missing ones are left out). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun statuses(documentIds: Collection<UUID>): Map<UUID, DocumentStatus> =
        repository.statuses(documentIds).mapValues { DocumentStatus.valueOf(it.value) }

    /**
     * Sets the status and records who did it (null: ERIKA itself, e.g. a bounce), when, and
     * why (AR-03), audited (AR-02). Returns false if the document doesn't exist or already has
     * this status.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun changeStatus(
        workspaceId: UUID,
        documentId: UUID,
        status: DocumentStatus,
        note: String?,
        actorId: UUID?,
    ): Boolean {
        val previous = repository.updateStatus(documentId, status.name) ?: return false
        repository.insertStatusEvent(workspaceId, documentId, previous, status.name, note?.trim()?.ifEmpty { null }, actorId)
        events.publishEvent(DocumentStatusChanged(workspaceId, actorId, documentId, previous, status.name))
        return true
    }

    /** Tags (trimmed, unique, in the given order) and the free note; null leaves a field unchanged. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun updateMetadata(
        workspaceId: UUID,
        documentId: UUID,
        tags: List<String>?,
        note: String?,
        actorId: UUID,
    ) {
        val changed = mutableListOf<String>()
        tags?.let {
            repository.updateTags(documentId, it.map(String::trim).filter(String::isNotEmpty).distinct())
            changed += "tags"
        }
        note?.let {
            repository.updateNote(documentId, it.trim().ifEmpty { null })
            changed += "note"
        }
        if (changed.isNotEmpty()) events.publishEvent(DocumentMetadataChanged(workspaceId, actorId, documentId, changed))
    }

    /** Reads and validates the document again with the current pack (a new result; history stays). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun revalidate(
        workspaceId: UUID,
        documentId: UUID,
        actorId: UUID,
    ) {
        repository.markPending(documentId)
        jobs.enqueue(
            ParseAndValidateJobHandler.TYPE,
            ParseAndValidateJobHandler.Payload(documentId),
            workspaceId = workspaceId,
            maxAttempts = ParseAndValidateJobHandler.MAX_ATTEMPTS,
        )
        events.publishEvent(RevalidationRequested(workspaceId, actorId, documentId))
    }
}
