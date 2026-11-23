package de.erika.identity

import de.erika.archive.Archive
import de.erika.platform.security.WorkspaceRole
import de.erika.platform.web.ErikaException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Roles and membership of a workspace (EX-02) and the account closure (EX-04). Runs in the
 * owner's request transaction (tenant-scoped). A removed or re-assigned member's sessions end
 * with their next request ([de.erika.identity.web.WorkspaceAccessInterceptor]).
 */
@Service
internal class Team(
    private val memberships: MembershipRepository,
    private val workspaces: WorkspaceRepository,
    private val archive: Archive,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun changeRole(
        workspaceId: UUID,
        actorId: UUID,
        memberId: UUID,
        role: WorkspaceRole,
    ): MembershipRow {
        val owners = memberships.lockOwners()
        val member = memberships.findInCurrentWorkspace(memberId) ?: throw IdentityErrors.notFound("MEMBER")
        if (member.role == role) return member
        if (member.role == WorkspaceRole.OWNER && owners.size <= 1) throw lastOwner()
        memberships.updateRole(memberId, role)
        events.publishEvent(MemberRoleChanged(workspaceId, actorId, member.userId, member.role.name, role.name))
        return member.copy(role = role)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun remove(
        workspaceId: UUID,
        actorId: UUID,
        memberId: UUID,
    ) {
        val owners = memberships.lockOwners()
        val member = memberships.findInCurrentWorkspace(memberId) ?: throw IdentityErrors.notFound("MEMBER")
        if (member.role == WorkspaceRole.OWNER && owners.size <= 1) throw lastOwner()
        memberships.delete(memberId)
        events.publishEvent(MemberRemoved(workspaceId, actorId, member.userId, member.role.name))
    }

    /**
     * Closes the account: read-only from now on, kept until the last retention date of its
     * originals (today if it has none). [confirmName] must repeat the workspace name.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun close(
        workspaceId: UUID,
        actorId: UUID,
        confirmName: String,
    ): WorkspaceRow {
        val workspace = workspaces.find(workspaceId) ?: throw IdentityErrors.notFound("WORKSPACE")
        if (workspace.closed) throw WorkspaceAccess.closed()
        if (confirmName.trim() != workspace.name.trim()) {
            throw ErikaException(
                "CONFIRMATION_MISMATCH",
                HttpStatus.BAD_REQUEST,
                "Type the workspace name exactly to confirm the closure.",
            )
        }
        val today = LocalDate.now()
        val deleteAfter = archive.lastRetentionDate(workspaceId)?.takeIf { it.isAfter(today) } ?: today
        val closed = workspaces.close(workspaceId, actorId, deleteAfter) ?: throw WorkspaceAccess.closed()
        events.publishEvent(WorkspaceClosed(workspaceId, actorId, deleteAfter))
        return closed
    }

    private fun lastOwner() =
        ErikaException(
            "LAST_OWNER",
            HttpStatus.CONFLICT,
            "A workspace needs at least one owner. Make someone else owner first.",
        )
}

/** Errors about access to the current workspace. */
object WorkspaceAccess {
    fun closed() =
        ErikaException(
            "WORKSPACE_CLOSED",
            HttpStatus.FORBIDDEN,
            "This account was closed. Its data can still be read and exported, but not changed.",
        )

    fun revoked() =
        ErikaException(
            "SESSION_REVOKED",
            HttpStatus.UNAUTHORIZED,
            "Your access to this workspace has changed. Please sign in again.",
        )
}
