package de.erika.identity

import de.erika.platform.mail.Outbox
import de.erika.platform.security.WorkspaceRole
import de.erika.platform.tenancy.TenantContext
import de.erika.platform.web.ErikaException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** State of an invitation as the API shows it. */
internal enum class InvitationState { OPEN, ACCEPTED, EXPIRED, REVOKED }

internal data class InvitationRow(
    val id: UUID,
    val workspaceId: UUID,
    val email: String,
    val role: WorkspaceRole,
    val invitedBy: UUID,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val acceptedAt: OffsetDateTime?,
    val revokedAt: OffsetDateTime?,
) {
    fun state(now: OffsetDateTime): InvitationState =
        when {
            acceptedAt != null -> InvitationState.ACCEPTED
            revokedAt != null -> InvitationState.REVOKED
            !expiresAt.isAfter(now) -> InvitationState.EXPIRED
            else -> InvitationState.OPEN
        }
}

/** An invitation found by its token, before the invitee has a session in that workspace. */
internal data class InvitationByToken(
    val id: UUID,
    val workspaceId: UUID,
    val workspaceName: String,
    val workspaceClosed: Boolean,
    val email: String,
    val role: WorkspaceRole,
    val expiresAt: OffsetDateTime,
    val acceptedAt: OffsetDateTime?,
    val revokedAt: OffsetDateTime?,
) {
    fun state(now: OffsetDateTime): InvitationState =
        when {
            acceptedAt != null -> InvitationState.ACCEPTED
            revokedAt != null -> InvitationState.REVOKED
            !expiresAt.isAfter(now) || workspaceClosed -> InvitationState.EXPIRED
            else -> InvitationState.OPEN
        }
}

/** A workspace the user belongs to (from `workspaces_of_user`, independent of the request's RLS scope). */
internal data class WorkspaceChoiceRow(
    val id: UUID,
    val name: String,
    val role: WorkspaceRole,
    val closed: Boolean,
)

@Repository
internal class InvitationRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        id: UUID,
        workspaceId: UUID,
        email: String,
        role: WorkspaceRole,
        tokenHash: ByteArray,
        invitedBy: UUID,
        validFor: Duration,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO invitation (id, workspace_id, email, role, token_hash, invited_by, expires_at)
                VALUES (:id, :ws, :email, :role, :hash, :by, now() + make_interval(secs => :seconds))
                """.trimIndent(),
            ).param("id", id)
            .param("ws", workspaceId)
            .param("email", email)
            .param("role", role.name)
            .param("hash", tokenHash)
            .param("by", invitedBy)
            .param("seconds", validFor.toSeconds())
            .update()
    }

    /** Revokes the open invitation(s) to this address in the current workspace. */
    fun revokeOpenFor(email: String): Int =
        jdbc
            .sql(
                """
                UPDATE invitation SET revoked_at = now()
                 WHERE workspace_id = current_workspace_id() AND lower(email) = lower(:email)
                   AND accepted_at IS NULL AND revoked_at IS NULL
                """.trimIndent(),
            ).param("email", email)
            .update()

    fun revoke(id: UUID): Boolean =
        jdbc
            .sql(
                """
                UPDATE invitation SET revoked_at = now()
                 WHERE id = :id AND workspace_id = current_workspace_id() AND accepted_at IS NULL AND revoked_at IS NULL
                """.trimIndent(),
            ).param("id", id)
            .update() == 1

    fun find(id: UUID): InvitationRow? =
        jdbc
            .sql("$SELECT WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    /** Open invitations and those of the last 30 days, newest first. */
    fun recent(): List<InvitationRow> =
        jdbc
            .sql(
                "$SELECT WHERE workspace_id = current_workspace_id() " +
                    "AND (accepted_at IS NULL AND revoked_at IS NULL OR created_at > now() - interval '30 days') " +
                    "ORDER BY created_at DESC, id LIMIT 100",
            ).query(::map)
            .list()

    /** Marks an open invitation as accepted (in its workspace's transaction). Returns false if it wasn't open. */
    fun markAccepted(
        id: UUID,
        userId: UUID,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE invitation SET accepted_at = now(), accepted_by = :user
                 WHERE id = :id AND workspace_id = current_workspace_id()
                   AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()
                """.trimIndent(),
            ).param("id", id)
            .param("user", userId)
            .update() == 1

    fun byToken(hash: ByteArray): InvitationByToken? =
        jdbc
            .sql("SELECT * FROM invitation_by_token(:hash)")
            .param("hash", hash)
            .query { rs, _ ->
                InvitationByToken(
                    id = rs.getObject("id", UUID::class.java),
                    workspaceId = rs.getObject("workspace_id", UUID::class.java),
                    workspaceName = rs.getString("workspace_name"),
                    workspaceClosed = rs.getBoolean("workspace_closed"),
                    email = rs.getString("email"),
                    role = WorkspaceRole.valueOf(rs.getString("role")),
                    expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
                    acceptedAt = rs.getObject("accepted_at", OffsetDateTime::class.java),
                    revokedAt = rs.getObject("revoked_at", OffsetDateTime::class.java),
                )
            }.optional()
            .orElse(null)

    fun workspacesOf(userId: UUID): List<WorkspaceChoiceRow> =
        jdbc
            .sql("SELECT id, name, role, closed FROM workspaces_of_user(:user)")
            .param("user", userId)
            .query { rs, _ ->
                WorkspaceChoiceRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("name"),
                    WorkspaceRole.valueOf(rs.getString("role")),
                    rs.getBoolean("closed"),
                )
            }.list()

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = InvitationRow(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        email = rs.getString("email"),
        role = WorkspaceRole.valueOf(rs.getString("role")),
        invitedBy = rs.getObject("invited_by", UUID::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
        acceptedAt = rs.getObject("accepted_at", OffsetDateTime::class.java),
        revokedAt = rs.getObject("revoked_at", OffsetDateTime::class.java),
    )

    private companion object {
        const val SELECT =
            "SELECT id, workspace_id, email, role, invited_by, created_at, expires_at, accepted_at, revoked_at FROM invitation"
    }
}

/**
 * Invitations to a workspace (EX-02): the owner invites by email as MEMBER or ADVISOR; the
 * link carries a random token (only its SHA-256 is stored) and is valid for 7 days. The
 * invitee accepts with an existing account (signed in with the invited address) or signs up
 * with the token.
 */
@Service
internal class Invitations(
    private val repository: InvitationRepository,
    private val memberships: MembershipRepository,
    private val users: UserRepository,
    private val workspaces: WorkspaceRepository,
    private val tenant: TenantContext,
    private val mails: IdentityMails,
    private val outbox: Outbox,
    private val events: ApplicationEventPublisher,
) {
    /** Creates (or renews) the invitation and queues the email. Runs in the owner's request transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun invite(
        workspaceId: UUID,
        inviterId: UUID,
        email: String,
        role: WorkspaceRole,
    ): UUID {
        require(role != WorkspaceRole.OWNER) { "Owners are promoted after joining, not invited" }
        val address = email.trim()
        if (memberships.isMemberByEmail(address)) {
            throw ErikaException("ALREADY_MEMBER", HttpStatus.CONFLICT, "This person is already a member of the workspace.")
        }
        repository.revokeOpenFor(address)
        val token = Tokens.newToken()
        val id = UUID.randomUUID()
        repository.insert(id, workspaceId, address, role, Tokens.sha256(token), inviterId, VALIDITY)
        val inviter = checkNotNull(users.findById(inviterId))
        val workspace = checkNotNull(workspaces.find(workspaceId))
        outbox.send(mails.invitation(address, inviter, workspace.name, role, token))
        events.publishEvent(InvitationSent(workspaceId, inviterId, id, role.name))
        return id
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun revoke(
        workspaceId: UUID,
        actorId: UUID,
        id: UUID,
    ) {
        repository.find(id) ?: throw IdentityErrors.notFound("INVITATION")
        if (repository.revoke(id)) events.publishEvent(InvitationRevoked(workspaceId, actorId, id))
    }

    /** The invitation behind a token, or 404 `INVITATION_INVALID`. */
    fun preview(token: String): InvitationByToken = repository.byToken(Tokens.sha256(token.trim())) ?: throw invalid()

    /**
     * Adds [userId] (whose address must be the invited one) to the inviting workspace. Needs a
     * transaction; scopes it to that workspace. Accepting an invitation already accepted by
     * the same user is harmless. Returns the invitation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun accept(
        token: String,
        userId: UUID,
        userEmail: String,
    ): InvitationByToken {
        val invitation = preview(token)
        if (!invitation.email.equals(userEmail.trim(), ignoreCase = true)) {
            throw ErikaException(
                "INVITATION_OTHER_ACCOUNT",
                HttpStatus.FORBIDDEN,
                "This invitation is for another email address. Sign in with the invited address.",
            )
        }
        tenant.enterWorkspace(invitation.workspaceId)
        when (invitation.state(OffsetDateTime.now())) {
            InvitationState.OPEN -> {
                if (!repository.markAccepted(invitation.id, userId)) throw notOpen()
                memberships.insertIfAbsent(invitation.workspaceId, userId, invitation.role)
                events.publishEvent(InvitationAccepted(invitation.workspaceId, userId, invitation.id, invitation.role.name))
            }

            InvitationState.ACCEPTED -> {
                if (memberships.access(userId) == null) throw notOpen()
            }

            else -> {
                throw notOpen()
            }
        }
        return invitation
    }

    private fun invalid() = ErikaException("INVITATION_INVALID", HttpStatus.NOT_FOUND, "The invitation link is invalid.")

    private fun notOpen() =
        ErikaException(
            "INVITATION_NOT_OPEN",
            HttpStatus.GONE,
            "This invitation has expired, was withdrawn or was already used. Ask for a new one.",
        )

    companion object {
        val VALIDITY: Duration = Duration.ofDays(7)
    }
}
