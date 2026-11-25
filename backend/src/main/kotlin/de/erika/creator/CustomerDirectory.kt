package de.erika.creator

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** What other modules may know about a customer: enough to show and address work for it. */
data class CustomerRef(
    val id: UUID,
    val name: String,
    val type: CustomerType,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val archived: Boolean,
)

/**
 * Published API of the `creator` module: look up customers of the current workspace (MARA
 * work orders, ADR 0036). Runs in the caller's tenant transaction, so RLS applies.
 */
@Service
class CustomerDirectory internal constructor(
    private val customers: CustomerRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun find(id: UUID): CustomerRef? = customers.find(id)?.toRef()

    /** The customers among [ids] that exist in this workspace, by ID. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun findAll(ids: Collection<UUID>): Map<UUID, CustomerRef> = customers.findAll(ids).map { it.toRef() }.associateBy { it.id }

    private fun Customer.toRef() = CustomerRef(id, name, customerType, street, postalCode, city, archived)
}
