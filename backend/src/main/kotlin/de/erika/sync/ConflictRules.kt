package de.erika.sync

/** What happens to one field of an update mutation (ADR 0038; vectors in `mara-rules/vectors/sync/`). */
enum class FieldDecision {
    /** Nobody else changed the field since the phone saw it: the phone's value is applied. */
    APPLY,

    /** Changed concurrently to the same value: nothing to do, no conflict. */
    SAME,

    /** Changed concurrently; the server's value stays (conflict, server wins). */
    KEEP_SERVER,

    /** Changed concurrently; the phone's value is applied (conflict, phone wins; note to the office). */
    APPLY_OVER,
}

/** What happens to a DELETE mutation (vectors in `mara-rules/vectors/sync/delete.json`). */
enum class DeleteDecision {
    /** Nobody changed the record since the phone saw it. */
    DELETE,

    /** Another device changed it meanwhile: a delete loses against a concurrent edit. */
    KEEP,
}

/** What happens to an edit of a deleted record (vectors in `mara-rules/vectors/sync/edit-deleted.json`). */
enum class EditDeletedDecision {
    /** Deleted after the phone last saw the record: the edit wins and the record comes back. */
    RESTORE,

    /** The phone already saw the delete: the edit is refused (NOT_FOUND). */
    REJECT,
}

object ConflictRules {
    /**
     * @param baseVersions the field versions the phone saw
     * @param serverVersions the field versions on the server now
     */
    fun decideDelete(
        baseVersions: Map<String, Long>,
        serverVersions: Map<String, Long>,
    ): DeleteDecision =
        if (serverVersions.any { (field, version) -> version > (baseVersions[field] ?: 0) }) DeleteDecision.KEEP else DeleteDecision.DELETE

    /**
     * @param maxBaseVersion the highest field version the phone sent
     * @param deletedVersion the sequence number of the delete
     */
    fun decideEditOfDeleted(
        maxBaseVersion: Long,
        deletedVersion: Long,
    ): EditDeletedDecision = if (deletedVersion > maxBaseVersion) EditDeletedDecision.RESTORE else EditDeletedDecision.REJECT

    /**
     * @param baseVersion the field's version as the phone saw it
     * @param serverVersion the field's version on the server now
     * @param sameValue whether the phone's value equals the server's
     */
    fun decide(
        policy: FieldPolicy,
        baseVersion: Long,
        serverVersion: Long,
        sameValue: Boolean,
    ): FieldDecision =
        when {
            serverVersion <= baseVersion -> if (sameValue) FieldDecision.SAME else FieldDecision.APPLY
            sameValue -> FieldDecision.SAME
            policy == FieldPolicy.SERVER_WINS -> FieldDecision.KEEP_SERVER
            else -> FieldDecision.APPLY_OVER
        }
}
