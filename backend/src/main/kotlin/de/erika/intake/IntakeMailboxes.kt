package de.erika.intake

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Published API of `intake`: a workspace's intake email address, e.g. so ERIKA's own invoice
 * lands in the customer's inbox like any other received e-invoice (ADR 0033). Runs in the
 * caller's transaction, which must be scoped to that workspace.
 */
@Service
class IntakeMailboxes internal constructor(
    private val addresses: IntakeAddresses,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun addressOf(workspaceId: UUID): String = addresses.addressOf(workspaceId)
}
