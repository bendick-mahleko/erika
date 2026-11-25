package de.erika

import de.erika.support.ApiIntegrationTest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Harness for `api/mobile.yaml` (MARA, ADR 0036–0038), generated from the contract like
 * `TenantIsolationIT` is from `openapi.yaml`:
 * - an operation without a token (`security: []`) must be on [publicAllowed] and answer
 *   without a session;
 * - every token operation needs a tenant-isolation fixture. Device tokens arrive in MARA
 *   Stage 3; until then a token operation in the contract fails this test, so none can slip
 *   in untested.
 */
class MobileContractIT : ApiIntegrationTest() {
    private val spec: Map<String, Any?> by lazy {
        @Suppress("UNCHECKED_CAST")
        Yaml().load<Map<String, Any?>>(File("../api/mobile.yaml").readText()) as Map<String, Any?>
    }

    /** Reachable without a token: the app status before sign-in only. */
    private val publicAllowed = setOf("GET /mobile/status")

    private data class Operation(
        val method: String,
        val path: String,
        val public: Boolean,
    )

    private fun operations(): List<Operation> {
        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        return paths.flatMap { (path, item) ->
            item
                .filterKeys { it in setOf("get", "post", "put", "patch", "delete", "head") }
                .map { (method, op) ->
                    @Suppress("UNCHECKED_CAST")
                    val security = (op as Map<String, Any?>)["security"] as List<*>?
                    Operation(method.uppercase(), path, security != null && security.isEmpty())
                }
        }
    }

    @TestFactory
    fun `every operation is covered`(): List<DynamicTest> =
        operations().map { op ->
            DynamicTest.dynamicTest("${op.method} ${op.path}") {
                if (op.public) {
                    assertTrue("${op.method} ${op.path}" in publicAllowed, "${op.path} is public (security: []) but not on the allow-list")
                    if (op.method == "GET") {
                        val response = client().get("/api/v1${op.path}")
                        assertEquals(200, response.status, "public operation must answer without a session: $response")
                    }
                } else {
                    fail("${op.method} ${op.path} needs a token: add its fixture to the mobile isolation harness (MARA Stage 3)")
                }
            }
        }

    @Test
    fun `status reports protocol versions, update advice and the server time`() {
        val plain = client().get("/api/v1/mobile/status").tree
        assertEquals(1, plain.path("protocol").path("current").asInt())
        assertEquals("0.1.0", plain.path("minAppVersion").asString())
        assertEquals("NONE", plain.path("appUpdate").asString())
        val skew = Duration.between(OffsetDateTime.parse(plain.path("serverTime").asString()), OffsetDateTime.now()).abs()
        assertTrue(skew < Duration.ofMinutes(1), "server time is current")

        val old = client().get("/api/v1/mobile/status", "X-App-Version" to "0.0.9").tree
        assertEquals("REQUIRED", old.path("appUpdate").asString())
        assertEquals(
            "NONE",
            client()
                .get("/api/v1/mobile/status", "X-App-Version" to "0.1.0")
                .tree
                .path("appUpdate")
                .asString(),
        )
    }

    @Test
    fun `other mobile paths still need sign-in`() {
        assertEquals(401, client().get("/api/v1/mobile/devices/me/status").status)
    }
}
