package de.erika.identity

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** The workspace as a business: name, address and tax IDs (the seller on outgoing invoices). */
data class WorkspaceProfile(
    val workspaceId: UUID,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val countryCode: String,
    val vatId: String?,
    val taxNumber: String?,
)

/**
 * Published API of the `identity` module: the business profile of a workspace, for the
 * invoice creator. Runs in the caller's transaction, which must be scoped to the workspace.
 */
@Service
class WorkspaceProfiles internal constructor(
    private val workspaces: WorkspaceRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun of(workspaceId: UUID): WorkspaceProfile {
        val row = checkNotNull(workspaces.find(workspaceId)) { "Workspace $workspaceId not visible in this transaction" }
        return WorkspaceProfile(row.id, row.name, row.street, row.postalCode, row.city, row.countryCode, row.vatId, row.taxNumber)
    }
}
