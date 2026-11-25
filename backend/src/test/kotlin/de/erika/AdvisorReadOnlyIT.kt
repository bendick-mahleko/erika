package de.erika

import de.erika.support.ApiIntegrationTest
import de.erika.support.TestClient
import de.erika.support.TestDatabase
import de.erika.support.TestUser
import org.junit.jupiter.api.AfterAll
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
 * EX-02, generated from `api/openapi.yaml`: a tax advisor (ADVISOR) can read, comment and
 * export, but every other state-changing operation answers 403, with real IDs of the
 * workspace, and the workspace's data is unchanged afterwards. New endpoints are covered
 * automatically; an operation can only be opened to advisors by adding it to [allowed] with
 * the reason (and to the security configuration).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdvisorReadOnlyIT : ApiIntegrationTest() {
    private lateinit var owner: TestClient
    private lateinit var advisor: TestClient
    private lateinit var ownerUser: TestUser
    private lateinit var fixtures: Map<String, String>

    /** The only write operations open to advisors, with the reason. */
    private val allowed =
        mapOf(
            "POST /feedback" to "feedback to the ERIKA team changes no workspace data (ADR 0030)",
            "POST /documents/{documentId}/comments" to "advisors comment on documents (EX-02)",
            "POST /exports" to "advisors export periods for bookkeeping (EX-01)",
            "POST /auth/invitation/accept" to "joins another workspace with the advisor's own account",
            "POST /auth/workspace" to "switches the advisor's own session to another of their workspaces",
            "POST /auth/password" to "changes the advisor's own password, not workspace data",
            "POST /auth/locale" to "changes the advisor's own language, not workspace data (NF-13)",
        )

    private val spec: Map<String, Any?> by lazy {
        @Suppress("UNCHECKED_CAST")
        Yaml().load<Map<String, Any?>>(File("../api/openapi.yaml").readText()) as Map<String, Any?>
    }

    @BeforeAll
    fun workspace() {
        owner = client()
        ownerUser = accounts.enrolled("advised-owner", owner, workspaceName = "Mandant GmbH")
        advisor = accounts.invited(owner, "ADVISOR").second
        val key = "Idempotency-Key"
        val customer =
            owner
                .post(
                    "/api/v1/customers",
                    mapOf("name" to "Kunde", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"),
                    key to UUID.randomUUID().toString(),
                ).tree
                .path("id")
                .asString()
        val article =
            owner
                .post(
                    "/api/v1/articles",
                    mapOf("name" to "Artikel", "unitCode" to "C62", "netPrice" to "1.00", "vatRate" to "STANDARD_19"),
                    key to UUID.randomUUID().toString(),
                ).tree
                .path("id")
                .asString()
        val draft =
            owner
                .post(
                    "/api/v1/invoices",
                    mapOf("format" to "ZUGFERD_EN16931", "lines" to emptyList<Any>()),
                    key to UUID.randomUUID().toString(),
                ).tree
                .path("id")
                .asString()
        val invitation =
            owner
                .post(
                    "/api/v1/invitations",
                    mapOf("email" to "noch-offen@example.test", "role" to "MEMBER"),
                    key to UUID.randomUUID().toString(),
                ).tree
                .path("id")
                .asString()
        val upload =
            owner
                .post("/api/v1/uploads", mapOf("fileName" to "a.xml", "size" to 10, "contentType" to "application/xml"))
                .tree
                .path("id")
                .asString()
        val ownerMember =
            owner
                .get("/api/v1/members")
                .tree
                .path("items")
                .values()
                .first { it.path("role").asString() == "OWNER" }
                .path("id")
                .asString()
        val document = forgedDocument(ownerUser.workspaceId)
        val export = UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO data_export (id, workspace_id, kind, requested_by) VALUES (?, ?, 'FULL', ?)",
            export,
            ownerUser.workspaceId,
            ownerUser.userId,
        )

        // MARA Stage 2: real site, staff level, checklist template and work order of the workspace.
        fun created(
            path: String,
            body: Map<String, Any?>,
        ) = owner
            .post(path, body, key to UUID.randomUUID().toString())
            .also { check(it.status == 201) { "$path: $it" } }
            .tree
            .path("id")
            .asString()
        val site = created("/api/v1/sites", mapOf("customerId" to customer, "name" to "Baustelle", "countryCode" to "DE"))
        val staffLevel = created("/api/v1/staff-levels", mapOf("name" to "Geselle", "hourlyRate" to "50.00"))
        val template =
            created(
                "/api/v1/checklist-templates",
                mapOf(
                    "name" to "Checkliste",
                    "items" to listOf(mapOf("key" to "a", "label" to "A", "kind" to "YES_NO", "required" to true)),
                ),
            )
        val workOrder =
            created(
                "/api/v1/work-orders",
                mapOf(
                    "customerId" to customer,
                    "title" to "Auftrag",
                    "assigneeIds" to emptyList<String>(),
                ),
            )
        fixtures =
            mapOf(
                "siteId" to site,
                "staffLevelId" to staffLevel,
                "checklistTemplateId" to template,
                "workOrderId" to workOrder,
                "memberId" to ownerMember,
                "uploadId" to upload,
                "documentId" to document.toString(),
                "attachmentIndex" to "0",
                "customerId" to customer,
                "articleId" to article,
                "invoiceId" to draft,
                "deliveryId" to UUID.randomUUID().toString(),
                "invitationId" to invitation,
                "exportId" to export.toString(),
                "senderId" to UUID.randomUUID().toString(),
                "intakeId" to UUID.randomUUID().toString(),
                "recurringId" to UUID.randomUUID().toString(),
            )
    }

    /** The advisor's export would read the forged (bytes-less) document: never run it. */
    @AfterAll
    fun dropQueuedExports() {
        TestDatabase.update(
            "UPDATE job SET state = 'SUCCEEDED' WHERE workspace_id = ? AND type = 'export.build' AND state = 'QUEUED'",
            ownerUser.workspaceId,
        )
    }

    @TestFactory
    fun `as tax advisor, every write operation is refused and nothing changes (EX-02)`(): List<DynamicTest> {
        val before = snapshot()
        val writes = operations().filter { !it.public && it.method != "GET" }
        val refused =
            writes.filter { "${it.method} ${it.path}" !in allowed }.map { op ->
                DynamicTest.dynamicTest("${op.method} ${op.path} → 403") {
                    val response = call(op)
                    assertEquals(403, response.status, "${op.method} ${op.path} as ADVISOR: $response")
                }
            }
        val unchanged = DynamicTest.dynamicTest("the workspace's data is unchanged") { assertEquals(before, snapshot()) }
        val stale =
            DynamicTest.dynamicTest("every allowed operation exists in the contract") {
                val known = writes.map { "${it.method} ${it.path}" }.toSet()
                assertTrue(known.containsAll(allowed.keys), "allowed but not in the contract: ${allowed.keys - known}")
            }
        val permitted =
            listOf("POST /feedback", "POST /documents/{documentId}/comments", "POST /exports").map { name ->
                DynamicTest.dynamicTest("$name is allowed: ${allowed[name]}") {
                    val op = writes.single { "${it.method} ${it.path}" == name }
                    val response = call(op)
                    assertTrue(response.status in 200..299, "$name as ADVISOR: $response")
                }
            }
        return refused + unchanged + stale + permitted
    }

    private fun call(op: Operation): de.erika.support.Response {
        val headers = mutableListOf<Pair<String, String>>()
        if ("Idempotency-Key" in op.headerParams) headers += "Idempotency-Key" to UUID.randomUUID().toString()
        if ("If-Match" in op.headerParams) {
            val etag = if (op.pathParams.isEmpty()) owner.get("/api/v1${op.path}").header("ETag") else null
            headers += "If-Match" to (etag ?: "\"0\"")
        }
        var path = op.path
        op.pathParams.forEach { name ->
            path =
                path.replace(
                    "{$name}",
                    fixtures[name] ?: fail("No fixture for path parameter '$name' (${op.path}); add one in AdvisorReadOnlyIT"),
                )
        }
        return advisor.send(op.method, "/api/v1$path", op.example, headers.toTypedArray())
    }

    /** What the owner can read about the workspace (lists without path parameters; exports change by design). */
    private fun snapshot(): Map<String, String> =
        operations()
            .filter { it.method == "GET" && !it.public && it.pathParams.isEmpty() && it.path != "/exports" && it.path != "/auth/session" }
            .associate { it.path to owner.get("/api/v1${it.path}").body } +
            fixtures.filterKeys { it in setOf("customerId", "articleId", "invoiceId", "documentId") }.mapValues { (k, id) ->
                val path =
                    mapOf(
                        "customerId" to "customers",
                        "articleId" to "articles",
                        "invoiceId" to "invoices",
                        "documentId" to "documents",
                    ).getValue(k)
                owner.get("/api/v1/$path/$id").body
            }

    private fun forgedDocument(workspaceId: UUID): UUID {
        val id = UUID.randomUUID()
        val sha = (id.toString().replace("-", "") + id.toString().replace("-", "")).take(64)
        TestDatabase.update(
            """
            INSERT INTO stored_object (workspace_id, sha256, size_bytes, media_type, storage_key, retention_until, object_lock)
            VALUES (?, ?, 1, 'application/xml', ?, '2034-12-31', 'NONE')
            """.trimIndent(),
            workspaceId,
            sha,
            "ws/$workspaceId/$sha",
        )
        TestDatabase.update(
            "INSERT INTO document (id, workspace_id, direction, sha256, media_type, size_bytes, received_via) VALUES (?, ?, 'IN', ?, 'application/xml', 1, 'UPLOAD')",
            id,
            workspaceId,
            sha,
        )
        return id
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
        val parameters = (spec["components"] as Map<String, Any?>)["parameters"] as Map<String, Any?>
        return paths.flatMap { (path, item) ->
            item.filterKeys { it in setOf("get", "post", "put", "patch", "delete") }.map { (method, raw) ->
                val op = raw as Map<String, Any?>
                val params =
                    (op["parameters"] as List<Map<String, Any?>>?).orEmpty().map { p ->
                        (p["\$ref"] as String?)?.let { parameters[it.substringAfterLast('/')] as Map<String, Any?> } ?: p
                    }
                val security = (op["security"] ?: globalSecurity) as List<*>?
                val body = op["requestBody"] as Map<String, Any?>?
                val content = body?.get("content") as Map<String, Any?>?
                val example =
                    (content?.get("application/json") as Map<String, Any?>?)?.get("example")
                        ?: if (content?.containsKey("multipart/form-data") == true) {
                            de.erika.support.MultipartFile
                                .png()
                        } else {
                            null
                        }
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
}
