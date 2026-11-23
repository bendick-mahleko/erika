package de.erika.identity.web

import de.erika.contract.api.InvitationsApi
import de.erika.contract.model.Invitation
import de.erika.contract.model.InvitationList
import de.erika.contract.model.InvitationRequest
import de.erika.contract.model.InvitationRole
import de.erika.contract.model.InvitationState
import de.erika.identity.InvitationRepository
import de.erika.identity.InvitationRow
import de.erika.identity.Invitations
import de.erika.identity.WorkspaceContacts
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.security.WorkspaceRole
import de.erika.platform.web.Idempotency
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

/** Inviting members and tax advisors (EX-02); owners only. */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class InvitationsController(
    private val invitations: Invitations,
    private val repository: InvitationRepository,
    private val contacts: WorkspaceContacts,
    private val idempotency: Idempotency,
) : InvitationsApi {
    @PreAuthorize("hasRole('OWNER')")
    override fun listInvitations(): ResponseEntity<InvitationList> {
        val rows = repository.recent()
        val names = contacts.displayNames(rows.map { it.invitedBy }.toSet())
        return ResponseEntity.ok(InvitationList(rows.map { it.toApi(names[it.invitedBy]) }))
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun createInvitation(
        idempotencyKey: String,
        invitationRequest: InvitationRequest,
    ): ResponseEntity<Invitation> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createInvitation") {
                invitations.invite(p.workspaceId, p.userId, invitationRequest.email, WorkspaceRole.valueOf(invitationRequest.role.value))
            }
        val row = checkNotNull(repository.find(result.resourceId))
        return ResponseEntity.status(HttpStatus.CREATED).body(row.toApi(contacts.displayNames(setOf(row.invitedBy))[row.invitedBy]))
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun revokeInvitation(invitationId: UUID): ResponseEntity<Unit> {
        val p = ErikaPrincipal.current()
        invitations.revoke(p.workspaceId, p.userId, invitationId)
        return ResponseEntity.noContent().build()
    }

    private fun InvitationRow.toApi(invitedByName: String?) =
        Invitation(
            id = id,
            email = email,
            role = InvitationRole.forValue(role.name),
            state = InvitationState.forValue(state(OffsetDateTime.now()).name),
            createdAt = createdAt,
            expiresAt = expiresAt,
            invitedByName = invitedByName,
        )
}
