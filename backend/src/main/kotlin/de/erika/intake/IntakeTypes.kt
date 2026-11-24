package de.erika.intake

import de.erika.platform.audit.AuditableEvent
import java.util.UUID

/** Mirrors the API's IntakeReason. */
enum class IntakeReason {
    VIRUS_FOUND,
    UNSUPPORTED_FILE_TYPE,
    FILE_TOO_LARGE,
    EMPTY_FILE,
    FILE_MISSING,
    ZIP_TOO_DEEP,
    ZIP_TOO_MANY_FILES,
    ZIP_BOMB,
    ZIP_ENCRYPTED,
    ZIP_DAMAGED,
    NO_ATTACHMENTS,
    ;

    /** Reasons that refuse a whole ZIP, as opposed to one file. */
    val zipLevel: Boolean get() = name.startsWith("ZIP_")
}

enum class IntakeOutcome { IMPORTED, ALREADY_IN_ARCHIVE, REJECTED }

enum class IntakeSource { UPLOAD, EMAIL }

enum class IntakeStatus { AWAITING_FILE, RECEIVED, PROCESSING, DONE, REJECTED, FAILED, QUARANTINED, DISCARDED }

/** What happened to one file of an intake. */
data class ItemResult(
    val fileName: String,
    val outcome: IntakeOutcome,
    val reason: IntakeReason? = null,
    val documentId: UUID? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

/** An upload or email has been processed (audited; drives the notification email). */
data class IntakeProcessed(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val intakeId: UUID,
    val source: IntakeSource,
    val imported: Int,
    val alreadyInArchive: Int,
    val rejected: Int,
    val reason: IntakeReason?,
) : AuditableEvent {
    override val action = "intake.processed"
    override val details
        get() =
            buildMap {
                put("intakeId", intakeId.toString())
                put("source", source.name)
                put("imported", imported.toString())
                put("alreadyInArchive", alreadyInArchive.toString())
                put("rejected", rejected.toString())
                reason?.let { put("reason", it.name) }
            }
}

/** A virus was found in a received file (NF-04). Audited; the file is not archived. */
data class VirusDetected(
    override val workspaceId: UUID,
    val intakeId: UUID,
    val signature: String,
) : AuditableEvent {
    override val actorId: UUID? = null
    override val action = "intake.virus.detected"
    override val details get() = mapOf("intakeId" to intakeId.toString(), "signature" to signature)
}
