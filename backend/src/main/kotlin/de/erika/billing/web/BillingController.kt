package de.erika.billing.web

import de.erika.billing.Billing
import de.erika.billing.BillingInvoiceRepository
import de.erika.billing.BillingPlan
import de.erika.billing.BillingProperties
import de.erika.billing.BillingState
import de.erika.billing.StripeEventInbox
import de.erika.billing.StripeException
import de.erika.billing.StripeSignature
import de.erika.contract.api.BillingApi
import de.erika.contract.model.BillingInvoice
import de.erika.contract.model.BillingInvoiceList
import de.erika.contract.model.BillingStatus
import de.erika.contract.model.CheckoutRequest
import de.erika.contract.model.PlanOffer
import de.erika.contract.model.RedirectUrl
import de.erika.contract.model.SubscriptionInfo
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.ErikaException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Instant
import de.erika.contract.model.BillingPlan as ApiPlan
import de.erika.contract.model.BillingState as ApiState

/** Billing page data, Stripe Checkout and portal (EX-03). Sessions are for owners only. */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class BillingController(
    private val billing: Billing,
    private val invoices: BillingInvoiceRepository,
    private val props: BillingProperties,
) : BillingApi {
    override fun getBilling(): ResponseEntity<BillingStatus> {
        val p = ErikaPrincipal.current()
        val account = billing.account(p.workspaceId)
        val state = billing.state(p.workspaceId)
        return ResponseEntity.ok(
            BillingStatus(
                enabled = props.enabled,
                state = ApiState.forValue(state.name),
                plans =
                    if (props.enabled) {
                        billing.offers().map {
                            PlanOffer(ApiPlan.forValue(it.plan.name), it.available, it.netAmount?.toPlainString(), it.currency)
                        }
                    } else {
                        emptyList()
                    },
                hasCustomer = account?.stripeCustomerId != null,
                trialEndsAt = account?.trialEndsAt?.takeIf { state == BillingState.TRIAL || state == BillingState.READ_ONLY },
                subscription =
                    account?.subscriptionStatus?.let {
                        SubscriptionInfo(
                            status = it,
                            cancelAtPeriodEnd = account.cancelAtPeriodEnd,
                            plan = billing.planOf(account.priceId)?.let { plan -> ApiPlan.forValue(plan.name) },
                            currentPeriodEnd = account.currentPeriodEnd,
                        )
                    },
            ),
        )
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun startCheckout(
        idempotencyKey: String,
        checkoutRequest: CheckoutRequest,
    ): ResponseEntity<RedirectUrl> {
        val p = ErikaPrincipal.current()
        val url = stripe { billing.checkout(p.workspaceId, p.userId, BillingPlan.valueOf(checkoutRequest.plan.value), idempotencyKey) }
        return ResponseEntity.ok(RedirectUrl(URI.create(url)))
    }

    @PreAuthorize("hasRole('OWNER')")
    override fun openBillingPortal(idempotencyKey: String): ResponseEntity<RedirectUrl> {
        val p = ErikaPrincipal.current()
        return ResponseEntity.ok(RedirectUrl(URI.create(stripe { billing.portal(p.workspaceId, idempotencyKey) })))
    }

    override fun listBillingInvoices(): ResponseEntity<BillingInvoiceList> =
        ResponseEntity.ok(
            BillingInvoiceList(
                invoices.ofCurrentWorkspace().map {
                    BillingInvoice(
                        id = it.id,
                        state = BillingInvoice.State.forValue(it.state),
                        currency = it.currency,
                        netAmount = it.net.toPlainString(),
                        taxAmount = it.tax.toPlainString(),
                        grossAmount = it.gross.toPlainString(),
                        paidAt = it.paidAt,
                        number = it.number,
                        issueDate = it.issueDate,
                        periodStart = it.periodStart,
                        periodEnd = it.periodEnd,
                    )
                },
            ),
        )

    private fun <T> stripe(call: () -> T): T =
        try {
            call()
        } catch (e: StripeException) {
            throw ErikaException(
                "PAYMENT_PROVIDER_ERROR",
                HttpStatus.BAD_GATEWAY,
                "The payment provider is not reachable right now. Please try again.",
                e,
            )
        }
}

/**
 * `POST /webhooks/stripe` (api/webhooks.yaml): no session; Stripe's signature over the raw body
 * is the authentication. The event is stored once and processed by the worker.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class StripeWebhookController(
    props: BillingProperties,
    private val inbox: StripeEventInbox,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val signature = StripeSignature(props.stripeWebhookSecret)

    @PostMapping("/webhooks/stripe", consumes = ["application/json"])
    fun receive(
        @RequestHeader(name = "Stripe-Signature", required = false) header: String?,
        @RequestBody body: String,
    ): ResponseEntity<Map<String, Boolean>> {
        if (!signature.isValid(header, body)) return ResponseEntity.badRequest().build()
        val event = runCatching { json.readTree(body) }.getOrNull() ?: return ResponseEntity.badRequest().build()
        val id = event.path("id").asString()
        val type = event.path("type").asString()
        val created = event.path("created")
        if (!id.startsWith("evt_") || type.isEmpty() || !created.isNumber) return ResponseEntity.badRequest().build()
        val fresh = inbox.receive(id, type, Instant.ofEpochSecond(created.asLong()), body)
        if (!fresh) log.info("Stripe event {} received again; already stored", id)
        return ResponseEntity.ok(mapOf("received" to true))
    }
}

/**
 * EX-03: a workspace in state READ_ONLY (trial over without a subscription, subscription unpaid
 * or cancelled) can read, download, export and pay, but not change anything (403
 * `SUBSCRIPTION_REQUIRED`). Runs in the request's tenant transaction.
 */
@Component
@Profile(RuntimeInstance.PROFILE_API)
internal class EntitlementInterceptor(
    private val billing: Billing,
    private val props: BillingProperties,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (!props.enabled || request.method in READ_METHODS) return true
        val principal = ErikaPrincipal.currentOrNull() ?: return true
        val path = request.requestURI
        if (ALLOWED.any { path == it || path.startsWith("$it/") }) return true
        if (billing.state(principal.workspaceId) == BillingState.READ_ONLY) {
            throw ErikaException(
                "SUBSCRIPTION_REQUIRED",
                HttpStatus.FORBIDDEN,
                "The trial has ended or the subscription is not paid. Data can still be read and exported; choose a plan to continue.",
            )
        }
        return true
    }

    private companion object {
        val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")
        val ALLOWED = listOf("/api/v1/billing", "/api/v1/exports", "/api/v1/feedback", "/api/v1/auth", "/api/v1/mobile/auth")
    }
}

@Configuration(proxyBeanMethods = false)
@Profile(RuntimeInstance.PROFILE_API)
internal class EntitlementConfiguration(
    private val interceptor: EntitlementInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**")
    }
}
