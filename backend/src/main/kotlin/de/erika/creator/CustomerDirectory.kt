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

/** What other modules may know about an article (MARA material lines). Prices stay in `creator`. */
data class ArticleRef(
    val id: UUID,
    val articleNumber: String?,
    val name: String,
    val unitCode: String,
    val archived: Boolean,
)

/** An article's price for invoicing (MARA Stage 8). */
data class ArticlePrice(
    val id: UUID,
    val articleNumber: String?,
    val name: String,
    val unitCode: String,
    val netPrice: java.math.BigDecimal,
    val vatRate: LineVatRate,
)

/** Published API of the `creator` module: look up articles of the current workspace (MARA Stage 5). */
@Service
class ArticleDirectory internal constructor(
    private val articles: ArticleRepository,
) {
    /** The articles among [ids] with their net price and VAT rate, by ID. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun prices(ids: Collection<UUID>): Map<UUID, ArticlePrice> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            articles.findAll(ids.distinct()).associate {
                it.id to
                    ArticlePrice(it.id, it.articleNumber, it.name, it.unitCode, it.netPrice, it.vatRate)
            }
        }

    /** The articles among [ids] that exist in this workspace, by ID. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun findAll(ids: Collection<UUID>): Map<UUID, ArticleRef> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            articles.findAll(ids.distinct()).associate { it.id to ArticleRef(it.id, it.articleNumber, it.name, it.unitCode, it.archived) }
        }
}
