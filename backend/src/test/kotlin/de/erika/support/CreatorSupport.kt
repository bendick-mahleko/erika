package de.erika.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/** Steps of the invoice creator through the real API, for the creator tests. */
class CreatorSupport(
    private val client: TestClient,
    private val port: Int,
) {
    private val http = HttpClient.newHttpClient()

    fun key() = "Idempotency-Key" to UUID.randomUUID().toString()

    /** Seller profile (workspace) and invoice settings complete for every format. */
    fun completeSeller(
        kleinunternehmer: Boolean = false,
        vatId: String? = "DE123456789",
        pattern: String = "RE-{YYYY}-{NNNN}",
    ) {
        val ws = client.get("/api/v1/workspace")
        val updated =
            client.put(
                "/api/v1/workspace",
                buildMap {
                    put("name", ws.tree.path("name").asString())
                    put("street", "Musterstraße 1")
                    put("postalCode", "10115")
                    put("city", "Berlin")
                    put("countryCode", "DE")
                    vatId?.let { put("vatId", it) }
                    put("taxNumber", "30/123/45678")
                },
                "If-Match" to ws.header("ETag")!!,
            )
        assertEquals(200, updated.status, updated.toString())
        val settings = client.get("/api/v1/invoice-settings")
        val saved =
            client.put(
                "/api/v1/invoice-settings",
                mapOf(
                    "contactName" to "Erika Mustermann",
                    "contactPhone" to "+49 30 1234567",
                    "contactEmail" to "rechnung@mustermann.example",
                    "iban" to "DE02 1203 0000 0000 2020 51",
                    "bic" to "BYLADEM1001",
                    "kleinunternehmer" to kleinunternehmer,
                    "numberPattern" to pattern,
                    "creditNotePattern" to "GS-{YYYY}-{NNNN}",
                    "defaultFormat" to "ZUGFERD_EN16931",
                    "paymentTermsDays" to 14,
                ),
                "If-Match" to settings.header("ETag")!!,
            )
        assertEquals(200, saved.status, saved.toString())
    }

    fun content(
        format: String = "ZUGFERD_EN16931",
        treatment: String = "DOMESTIC",
        buyerVatId: String? = null,
        country: String = "DE",
        unitCode: String = "HUR",
        lines: List<Map<String, Any?>>? = null,
    ): Map<String, Any?> =
        mapOf(
            "format" to format,
            "vatTreatment" to treatment,
            "buyer" to
                mapOf(
                    "customerNumber" to "K-1001",
                    "name" to "Kunde AG",
                    "street" to "Kundenweg 2",
                    "postalCode" to "80331",
                    "city" to if (country == "DE") "München" else "Paris",
                    "countryCode" to country,
                    "email" to "eingang@kunde.example",
                    "vatId" to buyerVatId,
                ),
            "deliveryDate" to LocalDate.now().minusDays(3).toString(),
            "buyerReference" to "K-1001",
            "lines" to (
                lines ?: listOf(
                    mapOf(
                        "name" to "Malerarbeiten",
                        "quantity" to "8",
                        "unitCode" to unitCode,
                        "netPrice" to "55.00",
                        "vatRate" to "STANDARD_19",
                    ),
                    mapOf("name" to "Fachbuch", "quantity" to "2", "unitCode" to "C62", "netPrice" to "24.99", "vatRate" to "REDUCED_7"),
                )
            ),
        )

    fun createDraft(content: Map<String, Any?>): Response {
        val r = client.post("/api/v1/invoices", content, key())
        assertEquals(201, r.status, r.toString())
        return r
    }

    fun save(
        id: String,
        content: Map<String, Any?>,
    ): Response {
        val etag = client.get("/api/v1/invoices/$id").header("ETag")!!
        return client.put("/api/v1/invoices/$id", content, "If-Match" to etag)
    }

    /** Issues the draft and runs the worker; returns the invoice afterwards. */
    fun issue(id: String): Response {
        val r = client.post("/api/v1/invoices/$id/issue", null, key())
        assertEquals(202, r.status, r.toString())
        WorkerContext.drain()
        return client.get("/api/v1/invoices/$id")
    }

    fun createAndIssue(content: Map<String, Any?> = content()): Response {
        val id = createDraft(content).tree.path("id").asString()
        return issue(id)
    }

    /** Raw GET with the client's cookies, for binary downloads. */
    fun rawGet(path: String): HttpResponse<ByteArray> {
        val cookies =
            client.cookies.cookieStore.cookies
                .joinToString("; ") { "${it.name}=${it.value}" }
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Cookie", cookies)
                .header("X-Forwarded-For", client.ip)
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }
}
