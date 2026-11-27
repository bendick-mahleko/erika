package de.erika.support

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for the Stripe API in integration tests (the real one needs keys and the internet;
 * the end-to-end run with a Stripe test clock is in docs/ops/billing.md). Records every request
 * and answers the calls ERIKA makes with minimal objects.
 */
object FakeStripe {
    data class Request(
        val method: String,
        val path: String,
        val params: Map<String, String>,
        val headers: Map<String, String>,
    )

    val requests = CopyOnWriteArrayList<Request>()
    private val counter = AtomicInteger(1)

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.readAllBytes().toString(UTF_8)
                val params =
                    body
                        .split('&')
                        .filter { it.contains('=') }
                        .associate { URLDecoder.decode(it.substringBefore('='), UTF_8) to URLDecoder.decode(it.substringAfter('='), UTF_8) }
                val path = exchange.requestURI.path
                requests +=
                    Request(
                        exchange.requestMethod,
                        path,
                        params,
                        exchange.requestHeaders.mapValues { it.value.joinToString(",") },
                    )
                val n = counter.getAndIncrement()
                val (status, answer) =
                    when {
                        exchange.requestHeaders.getFirst("Authorization") != "Bearer sk_test_erika" -> {
                            401 to
                                """{"error":{"type":"invalid_request_error"}}"""
                        }

                        path == "/v1/customers" -> {
                            200 to """{"id":"cus_test$n","object":"customer"}"""
                        }

                        path == "/v1/checkout/sessions" -> {
                            200 to
                                """{"id":"cs_test$n","url":"https://checkout.stripe.test/c/pay/cs_test$n"}"""
                        }

                        path == "/v1/billing_portal/sessions" -> {
                            200 to
                                """{"id":"bps_test$n","url":"https://billing.stripe.test/p/session/$n"}"""
                        }

                        path == "/v1/prices/price_monthly" -> {
                            200 to """{"id":"price_monthly","unit_amount":2900,"currency":"eur","recurring":{"interval":"month"}}"""
                        }

                        path == "/v1/prices/price_annual" -> {
                            200 to """{"id":"price_annual","unit_amount":29000,"currency":"eur","recurring":{"interval":"year"}}"""
                        }

                        path.startsWith("/v1/prices/price_mara") -> {
                            val seat = path.contains("seat")
                            val annual = path.endsWith("annual")
                            val amount = if (seat) (if (annual) 9000 else 900) else (if (annual) 39000 else 3900)
                            200 to
                                """{"id":"${path.substringAfterLast('/')}","unit_amount":$amount,"currency":"eur",""" +
                                """"recurring":{"interval":"${if (annual) "year" else "month"}"}}"""
                        }

                        path == "/v1/subscription_items" && exchange.requestMethod == "POST" -> {
                            200 to """{"id":"si_test$n","object":"subscription_item","quantity":${params["quantity"] ?: 1}}"""
                        }

                        path.startsWith("/v1/subscription_items/") && exchange.requestMethod == "POST" -> {
                            200 to
                                """{"id":"${path.substringAfterLast('/')}","object":"subscription_item","quantity":${params["quantity"]}}"""
                        }

                        path.startsWith("/v1/subscription_items/") && exchange.requestMethod == "DELETE" -> {
                            200 to """{"id":"${path.substringAfterLast('/')}","deleted":true}"""
                        }

                        path.startsWith("/v1/subscriptions/") && exchange.requestMethod == "DELETE" -> {
                            200 to """{"id":"${path.substringAfterLast('/')}","status":"canceled"}"""
                        }

                        else -> {
                            404 to """{"error":{"type":"invalid_request_error","code":"resource_missing"}}"""
                        }
                    }
                val bytes = answer.toByteArray(UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun requestsTo(path: String) = requests.filter { it.path == path }
}
