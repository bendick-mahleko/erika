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

object ConflictRules {
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
