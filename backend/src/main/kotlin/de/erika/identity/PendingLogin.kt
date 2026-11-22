package de.erika.identity

import de.erika.platform.security.WorkspaceRole
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Password accepted, second factor outstanding. Lives in the server-side session only;
 * it grants nothing except the second-factor endpoints.
 */
data class PendingLogin(
    val userId: UUID,
    val workspaceId: UUID,
    val role: WorkspaceRole,
    val needsEnrolment: Boolean,
    val startedAt: Instant,
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}
