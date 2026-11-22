package de.erika

import de.erika.support.ApiIntegrationTest
import de.erika.support.Response
import de.erika.support.TestClient
import de.erika.support.TestUser
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * NF-03 tenant-isolation harness, generated from `api/openapi.yaml`. Every operation in
 * the contract is exercised; new endpoints are covered automatically, and an endpoint
 * the harness can't exercise fails the build until a fixture is added below.
 *
 * Two tenants A and B. Signed in as A:
 * - operations with path parameters are called with **B's** IDs → must answer 404;
 * - operations without path parameters must not return any of B's identifiers or names;
 * - after all calls, B's data (as B sees it) is unchanged.
 * Without a session, every non-public operation answers 401.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationIT : ApiIntegrationTest() {
    private lateinit var a: TestClient
    private lateinit var b: TestClient
    private lateinit var userA: TestUser
    private lateinit var userB: TestUser
    private lateinit var fixturesB: Map<String, String>
    private lateinit var markersB: Set<String>

    private val spec: Map<String, Any?> by lazy {
        @Suppress("UNCHECKED_CAST")
        Yaml().load<Map<String, Any?>>(File("../api/openapi.yaml").readText()) as Map<String, Any?>
    }

    /** Operations reachable without a session: health and the sign-in flow only. */
    private val publicAllowed = Regex("^/(health|auth/.*)$")

    @BeforeAll
    fun tenants() {
        a = client()
        b = client()
        userA = accounts.enrolled("tenant-a", a, workspaceName = "Tenant A GmbH")
        userB = accounts.enrolled("tenant-b", b, workspaceName = "Tenant B GmbH")

        val memberB =
            b
                .get("/api/v1/members")
                .tree
                .path("items")
                .first()
        // Path parameter name → an ID that belongs to tenant B. Later stages add theirs here
        // (documentId, invoiceId, exportId …); unknown parameters fail the harness.
        fixturesB =
            mapOf(
                "memberId" to memberB.path("id").asString(),
            )
        markersB = setOf(userB.workspaceId.toString(), userB.userId.toString(), userB.email, "Tenant B GmbH") + fixturesB.values
    }

    @TestFactory
    fun `as tenant A, every operation with tenant B's IDs or data leaks nothing`(): List<DynamicTest> {
        val before = snapshotOfB()
        val tests =
            operations().filterNot { it.public }.map { op ->
                DynamicTest.dynamicTest("${op.method} ${op.path}") { exerciseAsA(op) }
            }
        return tests + DynamicTest.dynamicTest("tenant B's data is unchanged afterwards") { assertEquals(before, snapshotOfB()) }
    }

    @TestFactory
    fun `without a session, every non-public operation answers 401`(): List<DynamicTest> =
        operations().map { op ->
            DynamicTest.dynamicTest("${op.method} ${op.path}") {
                if (op.public) {
                    assertTrue(publicAllowed.matches(op.path), "${op.path} is public (security: []) but not on the allow-list")
                } else {
                    val response = client().send(op.method, url(op, fixturesB), op.example)
                    assertEquals(401, response.status, "${op.method} ${op.path}: $response")
                }
            }
        }

    private fun exerciseAsA(op: Operation) {
        val headers = mutableListOf<Pair<String, String>>()
        if (op.headerParams.contains("If-Match")) {
            // Optimistic locking: use A's own current version, as a client would.
            a.get("/api/v1${op.path}").header("ETag")?.let { headers += "If-Match" to it }
        }
        val response = a.send(op.method, url(op, fixturesB), op.example, headers.toTypedArray())
        if (op.pathParams.isNotEmpty()) {
            assertEquals(404, response.status, "${op.method} ${op.path} with tenant B's IDs must be 404: $response")
        } else {
            assertTrue(response.status < 500, "${op.method} ${op.path}: $response")
            assertNoMarkersOfB(op, response)
        }
    }

    private fun assertNoMarkersOfB(
        op: Operation,
        response: Response,
    ) {
        val leaked = markersB.filter { response.body.contains(it, ignoreCase = true) }
        assertTrue(leaked.isEmpty(), "${op.method} ${op.path} returned tenant B's data: $leaked")
    }

    /** Everything tenant B can read about itself through the API. */
    private fun snapshotOfB(): Map<String, String> =
        operations()
            .filter { it.method == "GET" && !it.public && it.pathParams.isEmpty() }
            .associate { it.path to b.get("/api/v1${it.path}").body }

    private fun url(
        op: Operation,
        ids: Map<String, String>,
    ): String {
        var path = op.path
        op.pathParams.forEach { name ->
            val id =
                ids[name]
                    ?: if (ids.isEmpty()) {
                        UUID.randomUUID().toString()
                    } else {
                        fail(
                            "No tenant-B fixture for path parameter '$name' (${op.path}); add one in TenantIsolationIT",
                        )
                    }
            path = path.replace("{$name}", id)
        }
        return "/api/v1$path"
    }

    private data class Operation(
        val method: String,
        val path: String,
        val public: Boolean,
        val pathParams: List<String>,
        val headerParams: List<String>,
        val example: Any?,
    )

    @Suppress("UNCHECKED_CAST")
    private fun operations(): List<Operation> {
        val globalSecurity = spec["security"] as List<*>?
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        return paths.flatMap { (path, item) ->
            item
                .filterKeys { it in setOf("get", "post", "put", "patch", "delete") }
                .map { (method, raw) ->
                    val op = raw as Map<String, Any?>
                    val params = (op["parameters"] as List<Map<String, Any?>>?).orEmpty().map(::resolve)
                    val security = (op["security"] ?: globalSecurity) as List<*>?
                    val body = op["requestBody"] as Map<String, Any?>?
                    val example =
                        (
                            (
                                body?.get(
                                    "content",
                                ) as Map<String, Any?>?
                            )?.get("application/json") as Map<String, Any?>?
                        )?.get("example")
                    if (body != null && example == null) fail("$method $path: request bodies need an `example` for the isolation harness")
                    Operation(
                        method = method.uppercase(),
                        path = path,
                        public = security != null && security.isEmpty(),
                        pathParams = params.filter { it["in"] == "path" }.map { it["name"] as String },
                        headerParams = params.filter { it["in"] == "header" }.map { it["name"] as String },
                        example = example,
                    )
                }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolve(param: Map<String, Any?>): Map<String, Any?> {
        val ref = param["\$ref"] as String? ?: return param
        val name = ref.substringAfterLast('/')
        return ((spec["components"] as Map<String, Any?>)["parameters"] as Map<String, Any?>)[name] as Map<String, Any?>
    }
}
