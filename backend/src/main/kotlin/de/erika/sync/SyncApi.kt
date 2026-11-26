package de.erika.sync

import de.erika.platform.security.WorkspaceRole
import java.util.UUID

/*
 * Published API of the `sync` module (ADR 0038). Feature modules make their records syncable by
 * implementing [SyncEntity] and report every change to [ChangeFeed]; `sync` never reads their
 * tables. The phone sees one change feed for office and app edits alike.
 */

/** Who wins when the office and a phone changed the same field concurrently (design §4). */
enum class FieldPolicy {
    /** The value already on the server stays (work-order planning fields: the office wins). */
    SERVER_WINS,

    /** The phone's value is applied (status and work notes: the technician wins). */
    DEVICE_WINS,

    /** The later write is applied, with a note to the office (customer data). */
    LAST_WRITER_WINS,
}

/** The phone's user, for scope and price visibility. */
data class SyncViewer(
    val userId: UUID,
    val workspaceId: UUID,
    val role: WorkspaceRole,
    val deviceId: UUID,
    /** Prices and rates only when the owner allowed it (ADR 0039); always for OWNER and MEMBER. */
    val canSeePrices: Boolean,
) {
    val isOffice: Boolean get() = role == WorkspaceRole.OWNER || role == WorkspaceRole.MEMBER
}

/** A rule a mutation broke; the phone shows [code] and reverts its change. */
class SyncRejection(
    val code: String,
) : RuntimeException(code)

/**
 * A kind of syncable record. Records are flat JSON objects of fields; every field the phone may
 * send has a [FieldPolicy] in [fields]. Writes run in the push request's savepoint and must
 * publish their usual audit events; they may throw [SyncRejection] or an `ErikaException`.
 */
interface SyncEntity {
    /** Stable name in the protocol, e.g. `work_order`. */
    val name: String

    /** Fields the phone may write, with their conflict rule. Others are read-only. */
    val fields: Map<String, FieldPolicy>

    /** IDs of all records of the current workspace (for the first sync of a workspace). */
    fun allIds(): List<UUID>

    /** The full current data of [ids] that exist in the current workspace. */
    fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>>

    /** What [viewer] may see of [data], or null when the record is outside their scope (sent as a removal). */
    fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ): Map<String, Any?>?

    /** Whether [viewer] may write [field] of an existing record. */
    fun mayWrite(
        field: String,
        viewer: SyncViewer,
    ): Boolean = true

    /** Creates a record with a phone-made ID (UUIDv7). Throw [SyncRejection] when not allowed. */
    fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ): Unit = throw SyncRejection("CREATE_NOT_ALLOWED")

    /** Changes [values] of an existing record; only fields that won their conflict are passed. */
    fun update(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ): Unit = throw SyncRejection("UPDATE_NOT_ALLOWED")
}

/** Whether a user may see prices; implemented by the module that owns the staff settings. */
interface PriceVisibility {
    fun canSeePrices(
        userId: UUID,
        role: WorkspaceRole,
    ): Boolean
}
