package de.erika.creator.web

import de.erika.contract.api.ArticlesApi
import de.erika.contract.api.CustomersApi
import de.erika.contract.model.ArticleInput
import de.erika.contract.model.ArticleList
import de.erika.contract.model.CustomerInput
import de.erika.contract.model.CustomerList
import de.erika.contract.model.InvoiceFormat
import de.erika.contract.model.VatRate
import de.erika.creator.Article
import de.erika.creator.ArticleRepository
import de.erika.creator.Customer
import de.erika.creator.CustomerRepository
import de.erika.creator.LineVatRate
import de.erika.creator.MasterDataChanged
import de.erika.creator.NumberTakenException
import de.erika.creator.VatTreatment
import de.erika.invoicecore.write.OutputFormat
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.ErikaException
import de.erika.platform.web.Etags
import de.erika.platform.web.Idempotency
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID
import de.erika.contract.model.Article as ApiArticle
import de.erika.contract.model.Customer as ApiCustomer
import de.erika.contract.model.VatTreatment as ApiVatTreatment

/** Customer list (CR-05). Reading for every role; changes for OWNER and MEMBER. */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class CustomersController(
    private val customers: CustomerRepository,
    private val idempotency: Idempotency,
    private val events: ApplicationEventPublisher,
) : CustomersApi {
    override fun listCustomers(
        q: String?,
        includeArchived: Boolean,
        limit: Int,
    ): ResponseEntity<CustomerList> = ResponseEntity.ok(CustomerList(customers.list(q, includeArchived, limit).map { it.toApi() }))

    override fun getCustomer(customerId: UUID): ResponseEntity<ApiCustomer> {
        val c = customers.find(customerId) ?: throw notFound()
        return ResponseEntity.ok().eTag(Etags.of(c.version)).body(c.toApi())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun createCustomer(
        idempotencyKey: String,
        customerInput: CustomerInput,
    ): ResponseEntity<ApiCustomer> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createCustomer") {
                val c = customerInput.toModel(UUID.randomUUID())
                taken { customers.insert(p.workspaceId, c) }
                events.publishEvent(MasterDataChanged(p.workspaceId, p.userId, "customer", c.id, created = true))
                c.id
            }
        val c = customers.find(result.resourceId) ?: throw notFound()
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Etags.of(c.version)).body(c.toApi())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun updateCustomer(
        customerId: UUID,
        customerInput: CustomerInput,
        ifMatch: String?,
    ): ResponseEntity<ApiCustomer> {
        val p = ErikaPrincipal.current()
        customers.find(customerId) ?: throw notFound()
        val expected = Etags.required(ifMatch)
        val saved = taken { customers.update(customerInput.toModel(customerId), expected) } ?: throw Etags.conflict()
        events.publishEvent(MasterDataChanged(p.workspaceId, p.userId, "customer", customerId, created = false))
        return ResponseEntity.ok().eTag(Etags.of(saved.version)).body(saved.toApi())
    }

    private fun notFound() = ErikaException("CUSTOMER_NOT_FOUND", HttpStatus.NOT_FOUND, "Customer not found.")

    private fun CustomerInput.toModel(id: UUID) =
        Customer(
            id = id,
            customerNumber = customerNumber.clean(),
            name = name.trim(),
            street = street.clean(),
            additionalLine = additionalLine.clean(),
            postalCode = postalCode.clean(),
            city = city.clean(),
            countryCode = countryCode,
            vatId = vatId.clean()?.uppercase()?.replace(" ", ""),
            email = email.clean(),
            contactName = contactName.clean(),
            buyerReference = buyerReference.clean(),
            vatTreatment = VatTreatment.valueOf(vatTreatment.value),
            preferredFormat = preferredFormat?.let { OutputFormat.valueOf(it.value) },
            paymentTermsDays = paymentTermsDays,
            archived = archived ?: false,
        )

    private fun Customer.toApi() =
        ApiCustomer(
            name = name,
            countryCode = countryCode,
            vatTreatment = ApiVatTreatment.forValue(vatTreatment.name),
            archived = archived,
            id = id,
            version = version,
            customerNumber = customerNumber,
            street = street,
            additionalLine = additionalLine,
            postalCode = postalCode,
            city = city,
            vatId = vatId,
            email = email,
            contactName = contactName,
            buyerReference = buyerReference,
            preferredFormat = preferredFormat?.let { InvoiceFormat.forValue(it.name) },
            paymentTermsDays = paymentTermsDays,
        )
}

/** Articles and services (CR-05). Reading for every role; changes for OWNER and MEMBER. */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class ArticlesController(
    private val articles: ArticleRepository,
    private val idempotency: Idempotency,
    private val events: ApplicationEventPublisher,
) : ArticlesApi {
    override fun listArticles(
        q: String?,
        includeArchived: Boolean,
        limit: Int,
    ): ResponseEntity<ArticleList> = ResponseEntity.ok(ArticleList(articles.list(q, includeArchived, limit).map { it.toApi() }))

    override fun getArticle(articleId: UUID): ResponseEntity<ApiArticle> {
        val a = articles.find(articleId) ?: throw notFound()
        return ResponseEntity.ok().eTag(Etags.of(a.version)).body(a.toApi())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun createArticle(
        idempotencyKey: String,
        articleInput: ArticleInput,
    ): ResponseEntity<ApiArticle> {
        val p = ErikaPrincipal.current()
        val result =
            idempotency.once(p.workspaceId, idempotencyKey, "createArticle") {
                val a = articleInput.toModel(UUID.randomUUID())
                taken { articles.insert(p.workspaceId, a) }
                events.publishEvent(MasterDataChanged(p.workspaceId, p.userId, "article", a.id, created = true))
                a.id
            }
        val a = articles.find(result.resourceId) ?: throw notFound()
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Etags.of(a.version)).body(a.toApi())
    }

    @PreAuthorize("hasAnyRole('OWNER', 'MEMBER')")
    override fun updateArticle(
        articleId: UUID,
        articleInput: ArticleInput,
        ifMatch: String?,
    ): ResponseEntity<ApiArticle> {
        val p = ErikaPrincipal.current()
        articles.find(articleId) ?: throw notFound()
        val expected = Etags.required(ifMatch)
        val saved = taken { articles.update(articleInput.toModel(articleId), expected) } ?: throw Etags.conflict()
        events.publishEvent(MasterDataChanged(p.workspaceId, p.userId, "article", articleId, created = false))
        return ResponseEntity.ok().eTag(Etags.of(saved.version)).body(saved.toApi())
    }

    private fun notFound() = ErikaException("ARTICLE_NOT_FOUND", HttpStatus.NOT_FOUND, "Article not found.")

    private fun ArticleInput.toModel(id: UUID) =
        Article(
            id = id,
            articleNumber = articleNumber.clean(),
            name = name.trim(),
            description = description.clean(),
            unitCode = unitCode,
            netPrice = decimal(netPrice, "netPrice"),
            vatRate = LineVatRate.valueOf(vatRate.value),
            archived = archived ?: false,
        )

    private fun Article.toApi() =
        ApiArticle(
            name = name,
            unitCode = unitCode,
            netPrice = netPrice.toPlainString(),
            vatRate = VatRate.forValue(vatRate.name),
            archived = archived,
            id = id,
            version = version,
            articleNumber = articleNumber,
            description = description,
        )
}

internal fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** A decimal from the API; the pattern is checked by bean validation, this guards the rest. */
internal fun decimal(
    value: String,
    field: String,
): BigDecimal =
    value.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
        ?: throw ErikaException("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, "$field is not a valid amount.")

internal fun <T> taken(block: () -> T): T =
    try {
        block()
    } catch (e: NumberTakenException) {
        throw ErikaException("NUMBER_TAKEN", HttpStatus.CONFLICT, "This number is already used for another entry.", e)
    }
