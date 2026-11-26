package de.erika

import de.erika.identity.Tokens
import de.erika.support.ApiIntegrationTest
import de.erika.support.MobileClient
import de.erika.support.Phones
import de.erika.support.TestDatabase
import de.erika.support.TestUser
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Harness for `api/mobile.yaml` (MARA, ADR 0036–0038), generated from the contract like
 * `TenantIsolationIT` is from `openapi.yaml`:
 * - an operation without a token (`security: []`) must be on [publicAllowed]; public GETs
 *   answer without a token, public POSTs validate their body (400, not 401 or 500);
 * - every token operation answers 401 without a token, and with tenant A's device token it
 *   works and never shows tenant B's data. Path parameters get tenant B's IDs from
 *   [fixturesB] and must answer 404; a new path parameter without a fixture fails here.
 */
class MobileContractIT : ApiIntegrationTest() {
    private val spec: Map<String, Any?> by lazy {
        @Suppress("UNCHECKED_CAST")
        Yaml().load<Map<String, Any?>>(File("../api/mobile.yaml").readText()) as Map<String, Any?>
    }

    /** Reachable without a token: the status before sign-in and the sign-in itself. */
    private val publicAllowed =
        setOf(
            "GET /mobile/status",
            "POST /mobile/auth/device-login",
            "POST /mobile/auth/refresh",
            "POST /mobile/auth/totp-enrolment",
        )

    private data class Operation(
        val method: String,
        val path: String,
        val public: Boolean,
        val example: Any?,
    )

    private data class Tenant(
        val user: TestUser,
        val deviceId: UUID,
    )

    private val tenants by lazy {
        val a = client()
        val userA = accounts.enrolled("mobil-a", a, workspaceName = "Tenant A Handwerk")
        val b = client()
        val userB = accounts.enrolled("mobil-b", b, workspaceName = "Tenant B Handwerk")
        Tenant(userA, device(userA)) to Tenant(userB, device(userB))
    }

    /** Tenant B's IDs per path parameter (none yet: Stage 4 adds work orders and sync). */
    private val fixturesB: Map<String, String> by lazy { emptyMap() }

    private val markersB by lazy {
        val b = tenants.second
        setOf(b.user.workspaceId.toString(), b.user.userId.toString(), b.user.email, b.deviceId.toString(), "Tenant B Handwerk")
    }

    /** A registered device of [user], written directly (the sign-in itself is tested in `MobileDevicesIT`). */
    private fun device(user: TestUser): UUID {
        val id = UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO mobile_device (id, workspace_id, user_id, name, platform) VALUES (?, ?, ?, 'Harness', 'ANDROID')",
            id,
            user.workspaceId,
            user.userId,
        )
        return id
    }

    /** A fresh access token for a device (every operation gets its own: sign-out revokes the device). */
    private fun phoneOf(tenant: Tenant): MobileClient {
        val deviceId = device(tenant.user)
        val token = Tokens.newToken()
        TestDatabase.update(
            "INSERT INTO mobile_token (token_hash, workspace_id, device_id, kind, expires_at) VALUES (?, ?, ?, 'ACCESS', now() + interval '15 minutes')",
            Tokens.sha256(token),
            tenant.user.workspaceId,
            deviceId,
        )
        return Phones(this).phone().apply { accessToken = token }
    }

    private fun operations(): List<Operation> {
        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        return paths.flatMap { (path, item) ->
            item
                .filterKeys { it in setOf("get", "post", "put", "patch", "delete", "head") }
                .map { (method, op) ->
                    @Suppress("UNCHECKED_CAST")
                    val operation = op as Map<String, Any?>
                    val security = operation["security"] as List<*>?

                    @Suppress("UNCHECKED_CAST")
                    val example =
                        ((operation["requestBody"] as Map<String, Any?>?)?.get("content") as Map<String, Map<String, Any?>>?)
                            ?.get("application/json")
                            ?.get("example")
                    Operation(method.uppercase(), path, security != null && security.isEmpty(), example)
                }
        }
    }

    @TestFactory
    fun `every operation is covered`(): List<DynamicTest> =
        operations().map { op ->
            DynamicTest.dynamicTest("${op.method} ${op.path}") {
                val name = "${op.method} ${op.path}"
                if (op.public) {
                    assertTrue(name in publicAllowed, "${op.path} is public (security: []) but not on the allow-list")
                    val response =
                        if (op.method == "GET") client().get("/api/v1${op.path}") else Phones(this).phone().post("/api/v1${op.path}", "{}")
                    val expected = if (op.method == "GET") 200 else 400
                    assertEquals(expected, response.status, "$name without a token: $response")
                    return@dynamicTest
                }
                val params = Regex("\\{([^}]+)}").findAll(op.path).map { it.groupValues[1] }.toList()
                val missing = params.filterNot { it in fixturesB }
                if (missing.isNotEmpty()) fail("$name: no tenant-B fixture for path parameter(s) $missing")
                val pathB = params.fold(op.path) { p, param -> p.replace("{$param}", fixturesB.getValue(param)) }

                val anonymous = Phones(this).phone().send(op.method, "/api/v1$pathB", op.example)
                assertEquals(401, anonymous.status, "$name must need a token: $anonymous")

                val response = phoneOf(tenants.first).send(op.method, "/api/v1$pathB", op.example)
                if (params.isNotEmpty()) {
                    assertEquals(404, response.status, "$name with tenant B's IDs must answer 404: $response")
                } else {
                    assertTrue(response.status in 200..299, "$name with tenant A's token: $response")
                }
                markersB.forEach { assertFalse(response.body.contains(it), "$name shows tenant B's data ($it): $response") }
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
    fun `a web session cookie is no device token`() {
        val web = client()
        accounts.enrolled("nur-web", web)
        assertEquals(401, web.get("/api/v1/mobile/devices/me/status").status)
    }

    @Test
    fun `a device token is no web session`() {
        val phone = phoneOf(tenants.first)
        assertEquals(401, phone.get("/api/v1/members").status, "bearer tokens only work under /api/v1/mobile")
    }
}
