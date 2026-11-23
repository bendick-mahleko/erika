package de.erika.identity.web

import de.erika.contract.api.MembersApi
import de.erika.contract.api.WorkspaceApi
import de.erika.contract.model.Member
import de.erika.contract.model.MemberList
import de.erika.contract.model.MemberRoleChange
import de.erika.contract.model.Workspace
import de.erika.contract.model.WorkspaceClosureRequest
import de.erika.contract.model.WorkspaceUpdate
import de.erika.identity.IdentityErrors
import de.erika.identity.MembershipRepository
import de.erika.identity.MembershipRow
import de.erika.identity.Team
import de.erika.identity.WorkspaceChanges
import de.erika.identity.WorkspaceRepository
import de.erika.identity.WorkspaceRow
import de.erika.identity.WorkspaceUpdated
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.security.WorkspaceRole
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import de.erika.contract.model.Role as ApiRole

/**
 * Workspace settings. The workspace is always the one in the session; the request runs
 * in the tenant transaction (TenantTransactionFilter), so RLS applies as well.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class WorkspaceController(
    private val workspaces: WorkspaceRepository,
    private val team: Team,
    private val events: ApplicationEventPublisher,
) : WorkspaceApi {
    override fun getWorkspace(): ResponseEntity<Workspace> {
        val row = workspaces.find(ErikaPrincipal.current().workspaceId) ?: throw IdentityErrors.notFound("WORKSPACE")
        return ResponseEntity.ok().eTag(etag(row)).body(row.toApi())
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun updateWorkspace(
        workspaceUpdate: WorkspaceUpdate,
        ifMatch: String?,
    ): ResponseEntity<Workspace> {
        val principal = ErikaPrincipal.current()
        val expected = ifMatch?.let(::parseEtag) ?: throw IdentityErrors.preconditionRequired()
        val before = workspaces.find(principal.workspaceId) ?: throw IdentityErrors.notFound("WORKSPACE")
        val changes =
            WorkspaceChanges(
                name = workspaceUpdate.name.trim(),
                street = workspaceUpdate.street.clean(),
                postalCode = workspaceUpdate.postalCode.clean(),
                city = workspaceUpdate.city.clean(),
                countryCode = workspaceUpdate.countryCode,
                vatId = workspaceUpdate.vatId.clean()?.uppercase(),
                taxNumber = workspaceUpdate.taxNumber.clean(),
            )
        val after = workspaces.update(principal.workspaceId, expected, changes) ?: throw IdentityErrors.versionConflict()
        val changed = changedFields(before, after)
        if (changed.isNotEmpty()) events.publishEvent(WorkspaceUpdated(principal.workspaceId, principal.userId, changed))
        return ResponseEntity.ok().eTag(etag(after)).body(after.toApi())
    }

    /** EX-04: the workspace becomes read-only; kept until the last retention date. */
    @PreAuthorize("hasRole('OWNER')")
    override fun closeWorkspace(workspaceClosureRequest: WorkspaceClosureRequest): ResponseEntity<Workspace> {
        val principal = ErikaPrincipal.current()
        val closed = team.close(principal.workspaceId, principal.userId, workspaceClosureRequest.confirmName)
        return ResponseEntity.ok().eTag(etag(closed)).body(closed.toApi())
    }

    private fun changedFields(
        a: WorkspaceRow,
        b: WorkspaceRow,
    ) = buildList {
        if (a.name != b.name) add("name")
        if (a.street != b.street || a.postalCode != b.postalCode || a.city != b.city || a.countryCode != b.countryCode) add("address")
        if (a.vatId != b.vatId) add("vatId")
        if (a.taxNumber != b.taxNumber) add("taxNumber")
    }

    private fun etag(row: WorkspaceRow) = "\"${row.version}\""

    private fun parseEtag(value: String): Int? =
        value
            .trim()
            .removePrefix("W/")
            .trim('"')
            .toIntOrNull()

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun WorkspaceRow.toApi() =
        Workspace(
            id = id,
            name = name,
            countryCode = countryCode,
            plan = plan,
            updatedAt = updatedAt,
            street = street,
            postalCode = postalCode,
            city = city,
            vatId = vatId,
            taxNumber = taxNumber,
            closedAt = closedAt,
            deleteAfter = deleteAfter,
        )
}

@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class MembersController(
    private val memberships: MembershipRepository,
    private val team: Team,
) : MembersApi {
    override fun listMembers(): ResponseEntity<MemberList> =
        ResponseEntity.ok(
            MemberList(
                items =
                    memberships.inCurrentWorkspace().map {
                        it.toApi()
                    },
            ),
        )

    override fun getMember(memberId: UUID): ResponseEntity<Member> {
        // Another workspace's member is indistinguishable from a missing one (NF-03: 404).
        val row = memberships.findInCurrentWorkspace(memberId) ?: throw IdentityErrors.notFound("MEMBER")
        return ResponseEntity.ok(row.toApi())
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun changeMemberRole(
        memberId: UUID,
        memberRoleChange: MemberRoleChange,
    ): ResponseEntity<Member> {
        val p = ErikaPrincipal.current()
        val row = team.changeRole(p.workspaceId, p.userId, memberId, WorkspaceRole.valueOf(memberRoleChange.role.name))
        return ResponseEntity.ok(row.toApi())
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun removeMember(memberId: UUID): ResponseEntity<Unit> {
        val p = ErikaPrincipal.current()
        team.remove(p.workspaceId, p.userId, memberId)
        return ResponseEntity.noContent().build()
    }

    private fun MembershipRow.toApi() =
        Member(
            id = id,
            userId = userId,
            displayName = displayName,
            email = email,
            role = ApiRole.valueOf(role.name),
            totpEnabled = totpEnabled,
        )
}
