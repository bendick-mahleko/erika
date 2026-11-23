package de.erika.identity

import de.erika.platform.audit.AuditableEvent
import java.util.UUID

/*
 * Domain events of the identity module. All are recorded in the audit log (NF-10:
 * logins and account changes). Details never contain passwords, codes or tokens.
 */

data class WorkspaceCreated(
    override val workspaceId: UUID,
    override val actorId: UUID,
) : AuditableEvent {
    override val action = "identity.workspace.created"
}

/** The user accepted the terms incl. the data-processing agreement (ADR 0030). */
data class TermsAccepted(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val version: String,
) : AuditableEvent {
    override val action = "identity.terms.accepted"
    override val details get() = mapOf("version" to version)
}

data class EmailVerified(
    override val workspaceId: UUID,
    override val actorId: UUID,
) : AuditableEvent {
    override val action = "identity.email.verified"
}

data class LoginSucceeded(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val method: String,
    val newDevice: Boolean,
) : AuditableEvent {
    override val action = "identity.login.succeeded"
    override val details get() = mapOf("method" to method, "newDevice" to newDevice.toString())
}

data class LoginFailed(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val step: String,
) : AuditableEvent {
    override val action = "identity.login.failed"
    override val details get() = mapOf("step" to step)
}

data class AccountLocked(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val minutes: Long,
) : AuditableEvent {
    override val action = "identity.account.locked"
    override val details get() = mapOf("minutes" to minutes.toString())
}

data class TotpEnrolled(
    override val workspaceId: UUID,
    override val actorId: UUID,
) : AuditableEvent {
    override val action = "identity.totp.enrolled"
}

data class LoggedOut(
    override val workspaceId: UUID,
    override val actorId: UUID,
) : AuditableEvent {
    override val action = "identity.logout"
}

data class WorkspaceUpdated(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val changedFields: List<String>,
) : AuditableEvent {
    override val action = "identity.workspace.updated"
    override val details get() = mapOf("fields" to changedFields.sorted().joinToString(","))
}
