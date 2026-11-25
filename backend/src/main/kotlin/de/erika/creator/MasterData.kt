package de.erika.creator

import de.erika.invoicecore.write.OutputFormat
import de.erika.platform.audit.AuditableEvent
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

/** A customer (CR-05), table `party`. */
data class Customer(
    val id: UUID,
    val customerNumber: String?,
    val name: String,
    val street: String?,
    val additionalLine: String?,
    val postalCode: String?,
    val city: String?,
    val countryCode: String,
    val vatId: String?,
    val email: String?,
    val contactName: String?,
    val buyerReference: String?,
    val vatTreatment: VatTreatment,
    val preferredFormat: OutputFormat?,
    val paymentTermsDays: Int?,
    val archived: Boolean,
    val version: Int = 0,
    /** BUSINESS or PRIVATE (MARA M-OF-01). */
    val customerType: CustomerType = CustomerType.BUSINESS,
    /** Leitweg-ID of a public authority (B2G). */
    val leitwegId: String? = null,
)

enum class CustomerType { BUSINESS, PRIVATE }

/** An article or service (CR-05). */
data class Article(
    val id: UUID,
    val articleNumber: String?,
    val name: String,
    val description: String?,
    val unitCode: String,
    val netPrice: BigDecimal,
    val vatRate: LineVatRate,
    val archived: Boolean,
    val version: Int = 0,
    /** Optional purchase price (MARA M-OF-04). */
    val purchasePrice: BigDecimal? = null,
)

/** Customer or article created or changed; values are not audited (personal data), only which record. */
data class MasterDataChanged(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val kind: String,
    val id: UUID,
    val created: Boolean,
) : AuditableEvent {
    override val action = if (created) "creator.$kind.created" else "creator.$kind.changed"
    override val details get() = mapOf("id" to id.toString())
}

/** Customer or article number already used in this workspace. */
class NumberTakenException : RuntimeException("number already used")

@Repository
internal class CustomerRepository(
    private val jdbc: JdbcClient,
) {
    fun list(
        q: String?,
        includeArchived: Boolean,
        limit: Int,
    ): List<Customer> {
        val like = q?.trim()?.takeIf { it.isNotEmpty() }?.let { "%${escapeLike(it)}%" }
        return jdbc
            .sql(
                """
                SELECT * FROM party
                 WHERE workspace_id = current_workspace_id()
                   AND (:archived OR NOT archived)
                   AND (CAST(:like AS text) IS NULL OR name ILIKE :like OR customer_number ILIKE :like OR email ILIKE :like)
                 ORDER BY lower(name), id
                 LIMIT :limit
                """.trimIndent(),
            ).param("archived", includeArchived)
            .param("like", like)
            .param("limit", limit)
            .query(::map)
            .list()
    }

    fun find(id: UUID): Customer? =
        jdbc
            .sql("SELECT * FROM party WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun findAll(ids: Collection<UUID>): List<Customer> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("SELECT * FROM party WHERE id IN (:ids) AND workspace_id = current_workspace_id()")
                .param("ids", ids.distinct())
                .query(::map)
                .list()
        }

    fun insert(
        workspaceId: UUID,
        c: Customer,
    ) {
        try {
            jdbc
                .sql(
                    """
                    INSERT INTO party (id, workspace_id, customer_number, name, street, additional_line, postal_code, city, country_code,
                                       vat_id, email, contact_name, buyer_reference, vat_treatment, preferred_format, payment_terms_days,
                                       archived, customer_type, leitweg_id)
                    VALUES (:id, :ws, :number, :name, :street, :additional, :postal, :city, :country, :vatId, :email, :contact,
                            :buyerRef, :treatment, :format, :days, :archived, :customerType, :leitwegId)
                    """.trimIndent(),
                ).bind(c)
                .param("ws", workspaceId)
                .update()
        } catch (e: DuplicateKeyException) {
            throw NumberTakenException().apply { initCause(e) }
        }
    }

    /** Optimistic update; null if [expectedVersion] is outdated. */
    fun update(
        c: Customer,
        expectedVersion: Int,
    ): Customer? {
        val n =
            try {
                jdbc
                    .sql(
                        """
                        UPDATE party SET customer_number = :number, name = :name, street = :street, additional_line = :additional,
                               postal_code = :postal, city = :city, country_code = :country, vat_id = :vatId, email = :email,
                               contact_name = :contact, buyer_reference = :buyerRef, vat_treatment = :treatment,
                               preferred_format = :format, payment_terms_days = :days, archived = :archived,
                               customer_type = :customerType, leitweg_id = :leitwegId,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = current_workspace_id() AND version = :expected
                        """.trimIndent(),
                    ).bind(c)
                    .param("expected", expectedVersion)
                    .update()
            } catch (e: DuplicateKeyException) {
                throw NumberTakenException().apply { initCause(e) }
            }
        return if (n == 1) find(c.id) else null
    }

    private fun JdbcClient.StatementSpec.bind(c: Customer) =
        param("id", c.id)
            .param("number", c.customerNumber)
            .param("name", c.name)
            .param("street", c.street)
            .param("additional", c.additionalLine)
            .param("postal", c.postalCode)
            .param("city", c.city)
            .param("country", c.countryCode)
            .param("vatId", c.vatId)
            .param("email", c.email)
            .param("contact", c.contactName)
            .param("buyerRef", c.buyerReference)
            .param("treatment", c.vatTreatment.name)
            .param("format", c.preferredFormat?.name)
            .param("days", c.paymentTermsDays)
            .param("archived", c.archived)
            .param("customerType", c.customerType.name)
            .param("leitwegId", c.leitwegId)

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = Customer(
        id = rs.getObject("id", UUID::class.java),
        customerNumber = rs.getString("customer_number"),
        name = rs.getString("name"),
        street = rs.getString("street"),
        additionalLine = rs.getString("additional_line"),
        postalCode = rs.getString("postal_code"),
        city = rs.getString("city"),
        countryCode = rs.getString("country_code"),
        vatId = rs.getString("vat_id"),
        email = rs.getString("email"),
        contactName = rs.getString("contact_name"),
        buyerReference = rs.getString("buyer_reference"),
        vatTreatment = VatTreatment.valueOf(rs.getString("vat_treatment")),
        preferredFormat = rs.getString("preferred_format")?.let(OutputFormat::valueOf),
        paymentTermsDays = rs.getObject("payment_terms_days") as Int?,
        archived = rs.getBoolean("archived"),
        version = rs.getInt("version"),
        customerType = CustomerType.valueOf(rs.getString("customer_type")),
        leitwegId = rs.getString("leitweg_id"),
    )
}

@Repository
internal class ArticleRepository(
    private val jdbc: JdbcClient,
) {
    fun list(
        q: String?,
        includeArchived: Boolean,
        limit: Int,
    ): List<Article> {
        val like = q?.trim()?.takeIf { it.isNotEmpty() }?.let { "%${escapeLike(it)}%" }
        return jdbc
            .sql(
                """
                SELECT * FROM article
                 WHERE workspace_id = current_workspace_id()
                   AND (:archived OR NOT archived)
                   AND (CAST(:like AS text) IS NULL OR name ILIKE :like OR article_number ILIKE :like)
                 ORDER BY lower(name), id
                 LIMIT :limit
                """.trimIndent(),
            ).param("archived", includeArchived)
            .param("like", like)
            .param("limit", limit)
            .query(::map)
            .list()
    }

    fun find(id: UUID): Article? =
        jdbc
            .sql("SELECT * FROM article WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun insert(
        workspaceId: UUID,
        a: Article,
    ) {
        try {
            jdbc
                .sql(
                    """
                    INSERT INTO article (id, workspace_id, article_number, name, description, unit_code, net_price, vat_rate, archived,
                                         purchase_price)
                    VALUES (:id, :ws, :number, :name, :description, :unit, :price, :rate, :archived, :purchase)
                    """.trimIndent(),
                ).bind(a)
                .param("ws", workspaceId)
                .update()
        } catch (e: DuplicateKeyException) {
            throw NumberTakenException().apply { initCause(e) }
        }
    }

    fun update(
        a: Article,
        expectedVersion: Int,
    ): Article? {
        val n =
            try {
                jdbc
                    .sql(
                        """
                        UPDATE article SET article_number = :number, name = :name, description = :description, unit_code = :unit,
                               net_price = :price, vat_rate = :rate, archived = :archived, purchase_price = :purchase,
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND workspace_id = current_workspace_id() AND version = :expected
                        """.trimIndent(),
                    ).bind(a)
                    .param("expected", expectedVersion)
                    .update()
            } catch (e: DuplicateKeyException) {
                throw NumberTakenException().apply { initCause(e) }
            }
        return if (n == 1) find(a.id) else null
    }

    private fun JdbcClient.StatementSpec.bind(a: Article) =
        param("id", a.id)
            .param("number", a.articleNumber)
            .param("name", a.name)
            .param("description", a.description)
            .param("unit", a.unitCode)
            .param("price", a.netPrice)
            .param("rate", a.vatRate.name)
            .param("archived", a.archived)
            .param("purchase", a.purchasePrice)

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = Article(
        id = rs.getObject("id", UUID::class.java),
        articleNumber = rs.getString("article_number"),
        name = rs.getString("name"),
        description = rs.getString("description"),
        unitCode = rs.getString("unit_code"),
        netPrice = rs.getBigDecimal("net_price").stripTrailingZeros().let { if (it.scale() < 2) it.setScale(2) else it },
        vatRate = LineVatRate.valueOf(rs.getString("vat_rate")),
        archived = rs.getBoolean("archived"),
        version = rs.getInt("version"),
        purchasePrice = rs.getBigDecimal("purchase_price")?.let(::money),
    )
}

/** Two decimals at least, without trailing zeros beyond that ("55.50", "1.2345"). */
internal fun money(value: BigDecimal): BigDecimal = value.stripTrailingZeros().let { if (it.scale() < 2) it.setScale(2) else it }

internal fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
