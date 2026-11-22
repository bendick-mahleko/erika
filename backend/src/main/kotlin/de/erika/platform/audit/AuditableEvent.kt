package de.erika.platform.audit

import java.util.UUID

/**
 * A domain event that the `audit` module records in the workspace's hash-chained
 * audit log (NF-10). Published with Spring's ApplicationEventPublisher inside the
 * transaction that makes the change, so the change and its audit entry commit together.
 *
 * [details] must never contain invoice content, passwords, tokens or secrets.
 */
interface AuditableEvent {
    val workspaceId: UUID

    /** The user who caused the event; null for system actions. */
    val actorId: UUID?

    /** Stable action name, e.g. `identity.login.succeeded`. */
    val action: String

    val details: Map<String, String> get() = emptyMap()
}
