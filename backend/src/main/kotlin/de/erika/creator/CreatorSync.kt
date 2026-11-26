package de.erika.creator

import de.erika.sync.ChangeFeed
import de.erika.sync.FieldPolicy
import de.erika.sync.SyncEntity
import de.erika.sync.SyncRejection
import de.erika.sync.SyncViewer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

/*
 * MARA Stage 4: customers and articles on the phone (ADR 0038). The phone sees what a
 * technician needs on site, not tax or invoicing details.
 */

private fun Any?.text(field: String): String? =
    when (this) {
        null -> null
        is String -> this.trim().ifEmpty { null }
        else -> throw SyncRejection("INVALID_${field.uppercase()}")
    }

/**
 * Customers, the whole workspace. Technicians create customers on site (M-MO-02) and correct
 * contact data; the later write wins, with a note to the office (design §4).
 */
@Component
internal class CustomerSyncEntity(
    private val customers: CustomerRepository,
    private val jdbc: JdbcClient,
    private val events: ApplicationEventPublisher,
) : SyncEntity {
    override val name = NAME

    override val fields =
        listOf("name", "customerType", "street", "additionalLine", "postalCode", "city", "countryCode", "email", "contactName", "phone")
            .associateWith { FieldPolicy.LAST_WRITER_WINS }

    override fun allIds(): List<UUID> =
        jdbc
            .sql(
                "SELECT id FROM party WHERE workspace_id = current_workspace_id() ORDER BY id",
            ).query(UUID::class.java)
            .list()
            .filterNotNull()

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        customers.findAll(ids).associate { c ->
            c.id to
                linkedMapOf(
                    "customerNumber" to c.customerNumber,
                    "name" to c.name,
                    "customerType" to c.customerType.name,
                    "street" to c.street,
                    "additionalLine" to c.additionalLine,
                    "postalCode" to c.postalCode,
                    "city" to c.city,
                    "countryCode" to c.countryCode,
                    "email" to c.email,
                    "contactName" to c.contactName,
                    "phone" to c.phone,
                    "archived" to c.archived,
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ) = data

    override fun create(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val customer =
            apply(
                Customer(
                    id = id,
                    customerNumber = null,
                    name = values["name"].text("name") ?: throw SyncRejection("NAME_REQUIRED"),
                    street = null,
                    additionalLine = null,
                    postalCode = null,
                    city = null,
                    countryCode = "DE",
                    vatId = null,
                    email = null,
                    contactName = null,
                    buyerReference = null,
                    vatTreatment = VatTreatment.DOMESTIC,
                    preferredFormat = null,
                    paymentTermsDays = null,
                    archived = false,
                    // Made on site, typically for a private household.
                    customerType = CustomerType.PRIVATE,
                ),
                values,
            )
        customers.insert(viewer.workspaceId, customer)
        events.publishEvent(MasterDataChanged(viewer.workspaceId, viewer.userId, "customer", id, created = true))
    }

    override fun update(
        id: UUID,
        values: Map<String, Any?>,
        viewer: SyncViewer,
    ) {
        val current = customers.find(id) ?: throw SyncRejection("NOT_FOUND")
        if (current.archived) throw SyncRejection("CUSTOMER_ARCHIVED")
        customers.update(apply(current, values), current.version) ?: throw SyncRejection("VERSION_CONFLICT")
        events.publishEvent(MasterDataChanged(viewer.workspaceId, viewer.userId, "customer", id, created = false))
    }

    private fun apply(
        c: Customer,
        v: Map<String, Any?>,
    ): Customer {
        fun pick(
            field: String,
            current: String?,
        ) = if (field in v) v[field].text(field) else current
        val country = pick("countryCode", c.countryCode) ?: "DE"
        if (!Regex("^[A-Z]{2}$").matches(country)) throw SyncRejection("INVALID_COUNTRYCODE")
        val email = pick("email", c.email)
        if (email != null && !Regex("^[^@\\s]+@[^@\\s]+$").matches(email)) throw SyncRejection("INVALID_EMAIL")
        return c.copy(
            name = pick("name", c.name) ?: throw SyncRejection("NAME_REQUIRED"),
            customerType =
                if ("customerType" in v) {
                    runCatching { CustomerType.valueOf(v["customerType"] as String) }.getOrNull()
                        ?: throw SyncRejection("INVALID_CUSTOMERTYPE")
                } else {
                    c.customerType
                },
            street = pick("street", c.street),
            additionalLine = pick("additionalLine", c.additionalLine),
            postalCode = pick("postalCode", c.postalCode),
            city = pick("city", c.city),
            countryCode = country,
            email = email,
            contactName = pick("contactName", c.contactName),
            phone = pick("phone", c.phone)?.take(50),
        )
    }

    companion object {
        const val NAME = "customer"
    }
}

/** Articles, read-only on the phone; prices only for users who may see them (ADR 0039). */
@Component
internal class ArticleSyncEntity(
    private val articles: ArticleRepository,
    private val jdbc: JdbcClient,
) : SyncEntity {
    override val name = NAME
    override val fields = emptyMap<String, FieldPolicy>()

    override fun allIds(): List<UUID> =
        jdbc
            .sql(
                "SELECT id FROM article WHERE workspace_id = current_workspace_id() ORDER BY id",
            ).query(UUID::class.java)
            .list()
            .filterNotNull()

    override fun load(ids: Collection<UUID>): Map<UUID, Map<String, Any?>> =
        articles.findAll(ids).associate { a ->
            a.id to
                linkedMapOf(
                    "articleNumber" to a.articleNumber,
                    "name" to a.name,
                    "description" to a.description,
                    "unitCode" to a.unitCode,
                    "netPrice" to money(a.netPrice).toPlainString(),
                    "vatRate" to a.vatRate.name,
                    "archived" to a.archived,
                )
        }

    override fun view(
        data: Map<String, Any?>,
        viewer: SyncViewer,
    ): Map<String, Any?> = if (viewer.canSeePrices) data else data + ("netPrice" to null)

    companion object {
        const val NAME = "article"
    }
}

/** Customer and article changes go into the change feed, whoever made them (ADR 0038). */
@Component
internal class CreatorFeed(
    private val feed: ChangeFeed,
) {
    @EventListener
    fun on(event: MasterDataChanged) {
        when (event.kind) {
            "customer" -> feed.changed(CustomerSyncEntity.NAME, event.id)
            "article" -> feed.changed(ArticleSyncEntity.NAME, event.id)
        }
    }
}
