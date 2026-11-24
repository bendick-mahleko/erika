package de.erika.billing

import de.erika.platform.web.ErikaException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("erika.billing")
data class BillingProperties(
    /** Off: no trial limits, no Stripe (local development and the closed beta). */
    val enabled: Boolean = false,
    val trialDays: Long = 14,
    /** Stripe secret key (`sk_live_…` / `sk_test_…`). */
    val stripeSecretKey: String = "",
    /** Signing secret of the webhook endpoint (`whsec_…`). */
    val stripeWebhookSecret: String = "",
    val stripeApiBase: String = "https://api.stripe.com",
    /** Pinned API version; the webhook endpoint in Stripe must use the same one (docs/ops/billing.md). */
    val stripeApiVersion: String = "2025-09-30.clover",
    /** Stripe price IDs (`price_…`); empty = plan not offered. */
    val priceMonthly: String = "",
    val priceAnnual: String = "",
    /** Stripe tax rate (19 % exclusive) for customers that pay German VAT; empty = none. */
    val taxRateId: String = "",
    /** The workspace that issues ERIKA's own invoices (ERIKA's company data and number pattern). */
    val issuerWorkspaceId: UUID? = null,
    /** Also send ERIKA's invoice to the customer's intake address, so it appears in their inbox. */
    val copyToInbox: Boolean = true,
)

/** A failed call to Stripe (message without customer data). */
class StripeException(
    val status: Int,
    val stripeCode: String?,
    message: String,
) : RuntimeException(message)

/**
 * Minimal Stripe REST client (form-encoded requests, JSON answers) for the calls ERIKA makes:
 * customers, Checkout and portal sessions, prices, cancelling a subscription. Every call
 * sends the pinned `Stripe-Version`; creating calls send an `Idempotency-Key`.
 */
@Component
class StripeClient(
    private val props: BillingProperties,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val prices = ConcurrentHashMap<String, Pair<Instant, JsonNode>>()
    private val http: RestClient =
        RestClient
            .builder()
            .baseUrl(props.stripeApiBase)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(5))
                    setReadTimeout(Duration.ofSeconds(20))
                },
            ).defaultHeader("Stripe-Version", props.stripeApiVersion)
            .build()

    fun createCustomer(
        params: Map<String, String?>,
        idempotencyKey: String,
    ): JsonNode = post("/v1/customers", params, idempotencyKey)

    fun createCheckoutSession(
        params: Map<String, String?>,
        idempotencyKey: String,
    ): JsonNode = post("/v1/checkout/sessions", params, idempotencyKey)

    fun createPortalSession(
        params: Map<String, String?>,
        idempotencyKey: String,
    ): JsonNode = post("/v1/billing_portal/sessions", params, idempotencyKey)

    /** Cancels at once. A subscription Stripe no longer knows (or already cancelled) counts as done. */
    fun cancelSubscription(subscriptionId: String) {
        try {
            call {
                http
                    .delete()
                    .uri("/v1/subscriptions/{id}", subscriptionId)
                    .headers(::auth)
                    .retrieve()
                    .body(String::class.java)
            }
        } catch (e: StripeException) {
            if (e.status == 404 || e.stripeCode == "resource_missing") {
                log.warn("Subscription {} unknown to Stripe; treated as cancelled", subscriptionId)
                return
            }
            throw e
        }
    }

    /** A price (amount, currency, interval), cached for an hour; null if Stripe can't be reached. */
    fun price(priceId: String): JsonNode? {
        prices[priceId]?.let { (at, node) -> if (at.isAfter(Instant.now().minus(PRICE_CACHE))) return node }
        return try {
            val node =
                call {
                    http
                        .get()
                        .uri("/v1/prices/{id}", priceId)
                        .headers(::auth)
                        .retrieve()
                        .body(String::class.java)
                }
            prices[priceId] = Instant.now() to node
            node
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("Stripe price {} not available: {}", priceId, e.message)
            null
        }
    }

    private fun post(
        path: String,
        params: Map<String, String?>,
        idempotencyKey: String,
    ): JsonNode =
        call {
            http
                .post()
                .uri(path)
                .headers(::auth)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form(params))
                .retrieve()
                .body(String::class.java)
        }

    private fun call(request: () -> String?): JsonNode {
        if (props.stripeSecretKey.isBlank()) {
            throw ErikaException("BILLING_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE, "Payments are not set up yet.")
        }
        try {
            return json.readTree(request() ?: "{}")
        } catch (e: RestClientResponseException) {
            val error = runCatching { json.readTree(e.responseBodyAsString).path("error") }.getOrNull()
            val code = error?.path("code")?.asString()?.takeIf { it.isNotEmpty() }
            throw StripeException(
                e.statusCode.value(),
                code,
                "Stripe answered ${e.statusCode.value()} (${code ?: error?.path("type")?.asString()})",
            )
        }
    }

    private fun auth(headers: org.springframework.http.HttpHeaders) = headers.setBearerAuth(props.stripeSecretKey)

    private companion object {
        val PRICE_CACHE: Duration = Duration.ofHours(1)

        /** `a[b][c]=v` form encoding as Stripe expects; null values are left out. */
        fun form(params: Map<String, String?>): String =
            params.entries
                .filter { it.value != null }
                .joinToString("&") { (k, v) -> "${URLEncoder.encode(k, UTF_8)}=${URLEncoder.encode(v, UTF_8)}" }
    }
}

/**
 * Stripe webhook signatures: header `t=<unix time>,v1=<hex HMAC-SHA256 of "t.body">` with the
 * endpoint secret as key. Several v1 values are possible while a secret is rolled.
 */
class StripeSignature(
    private val secret: String,
    private val clock: Clock = Clock.systemUTC(),
    private val tolerance: Duration = Duration.ofMinutes(5),
) {
    fun isValid(
        header: String?,
        payload: String,
    ): Boolean {
        if (secret.isBlank() || header.isNullOrBlank()) return false
        val parts = header.split(',').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
        val timestamp =
            parts
                .firstOrNull { it[0].trim() == "t" }
                ?.get(1)
                ?.trim()
                ?.toLongOrNull() ?: return false
        if (Duration.between(Instant.ofEpochSecond(timestamp), clock.instant()).abs() > tolerance) return false
        val expected = sign(timestamp, payload).toByteArray(UTF_8)
        return parts.filter { it[0].trim() == "v1" }.any { MessageDigest.isEqual(expected, it[1].trim().toByteArray(UTF_8)) }
    }

    fun sign(
        timestamp: Long,
        payload: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256")) }
        return HexFormat.of().formatHex(mac.doFinal("$timestamp.$payload".toByteArray(UTF_8)))
    }

    /** The header value Stripe would send now (tests and the local CLI replacement). */
    fun header(payload: String): String {
        val t = clock.instant().epochSecond
        return "t=$t,v1=${sign(t, payload)}"
    }
}
