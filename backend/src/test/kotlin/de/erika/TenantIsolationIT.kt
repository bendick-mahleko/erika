package de.erika

import de.erika.support.ApiIntegrationTest
import de.erika.support.Response
import de.erika.support.TestClient
import de.erika.support.TestDatabase
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
        val uploadB =
            b
                .post("/api/v1/uploads", mapOf("fileName" to "b.xml", "size" to 10, "contentType" to "application/xml"))
                .tree
                .path("id")
                .asString()
        val documentB = documentOf(userB.workspaceId)
        val key = "Idempotency-Key"
        val customerB =
            b
                .post(
                    "/api/v1/customers",
                    mapOf("name" to "Kunde von B", "countryCode" to "DE", "vatTreatment" to "DOMESTIC"),
                    key to UUID.randomUUID().toString(),
                ).tree
                .path("id")
                .asString()
        val articleB =
            b
                .post(
                    "/api/v1/articles",
                    mapOf("name" to "Artikel von B", "unitCode" to "C62", "netPrice" to "1.00", "vatRate" to "STANDARD_19"),
                    key to UUID.randomUUID().toString(),
                ).tree
                .path("id")
                .asString()
        val (invoiceB, deliveryB) = issuedInvoiceOf(userB.workspaceId, documentB)
        val invitationB =
            b
                .post(
                    "/api/v1/invitations",
                    mapOf("email" to "berater-von-b@example.test", "role" to "ADVISOR"),
                    key to UUID.randomUUID().toString(),
                ).tree
                .path("id")
                .asString()
        val exportB = UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO data_export (id, workspace_id, kind, requested_by) VALUES (?, ?, 'FULL', ?)",
            exportB,
            userB.workspaceId,
            userB.userId,
        )
        // Release 1.1: an allowed sender, a quarantined email and a recurring invoice of B.
        val senderB = UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO intake_sender (id, workspace_id, pattern) VALUES (?, ?, '@lieferant-von-b.example')",
            senderB,
            userB.workspaceId,
        )
        val intakeB = UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO intake (id, workspace_id, source, status, sender, subject) VALUES (?, ?, 'EMAIL', 'QUARANTINED', 'x@unbekannt.example', 'Rechnung')",
            intakeB,
            userB.workspaceId,
        )
        val recurringB = UUID.randomUUID()
        TestDatabase.update(
            """
            INSERT INTO recurring_invoice (id, workspace_id, name, source_invoice_id, content, interval, next_run)
            VALUES (?, ?, 'Wartung B', ?, '{}', 'MONTHLY', current_date + 30)
            """.trimIndent(),
            recurringB,
            userB.workspaceId,
            invoiceB,
        )

        // MARA Stage 2: a site, staff level, checklist template and work order of B.
        fun createdByB(
            path: String,
            body: Map<String, Any?>,
        ) = b
            .post(path, body, key to UUID.randomUUID().toString())
            .also { check(it.status == 201) { "$path as B: $it" } }
            .tree
            .path("id")
            .asString()
        val siteB = createdByB("/api/v1/sites", mapOf("customerId" to customerB, "name" to "Baustelle von B", "countryCode" to "DE"))
        val staffLevelB = createdByB("/api/v1/staff-levels", mapOf("name" to "Geselle von B", "hourlyRate" to "50.00"))
        val templateB =
            createdByB(
                "/api/v1/checklist-templates",
                mapOf(
                    "name" to "Checkliste von B",
                    "items" to listOf(mapOf("key" to "a", "label" to "A", "kind" to "YES_NO", "required" to true)),
                ),
            )
        val workOrderB =
            createdByB(
                "/api/v1/work-orders",
                mapOf("customerId" to customerB, "siteId" to siteB, "title" to "Auftrag von B", "assigneeIds" to listOf(userB.userId)),
            )
        // MARA Stage 3: a phone of B.
        val deviceB = UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO mobile_device (id, workspace_id, user_id, name, platform) VALUES (?, ?, ?, 'Telefon von B', 'ANDROID')",
            deviceB,
            userB.workspaceId,
            userB.userId,
        )
        fixturesB =
            mapOf(
                "deviceId" to deviceB.toString(),
                "siteId" to siteB,
                "staffLevelId" to staffLevelB,
                "checklistTemplateId" to templateB,
                "workOrderId" to workOrderB,
                "memberId" to memberB.path("id").asString(),
                "uploadId" to uploadB,
                "documentId" to documentB.toString(),
                "attachmentIndex" to "0",
                "customerId" to customerB,
                "articleId" to articleB,
                "invoiceId" to invoiceB.toString(),
                "deliveryId" to deliveryB.toString(),
                "invitationId" to invitationB,
                "exportId" to exportB.toString(),
                "senderId" to senderB.toString(),
                "intakeId" to intakeB.toString(),
                "recurringId" to recurringB.toString(),
            )
        markersB =
            setOf(userB.workspaceId.toString(), userB.userId.toString(), userB.email, "Tenant B GmbH", "berater-von-b@example.test") +
            fixturesB.values - "0"
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
                    val headers = if (op.headerParams.contains("Idempotency-Key")) arrayOf("Idempotency-Key" to "k") else emptyArray()
                    val response = client().send(op.method, url(op, fixturesB), op.example, headers)
                    assertEquals(401, response.status, "${op.method} ${op.path}: $response")
                }
            }
        }

    /** A document of tenant B, written directly (processing it needs the worker, not the point here). */
    private fun documentOf(workspaceId: UUID): UUID {
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

    /** An issued invoice of tenant B with one delivery, written directly (issuing needs the worker). */
    private fun issuedInvoiceOf(
        workspaceId: UUID,
        documentId: UUID,
    ): Pair<UUID, UUID> {
        val invoice = UUID.randomUUID()
        val delivery = UUID.randomUUID()
        TestDatabase.update(
            """
            INSERT INTO invoice (id, workspace_id, number, kind, type_code, format, document_id, sha256, file_name, media_type,
                                 issue_date, buyer_name, currency, net, tax, gross)
            SELECT ?, ?, 'B-1', 'INVOICE', '380', 'XRECHNUNG_CII', d.id, d.sha256, 'b.xml', 'application/xml',
                   current_date, 'Kunde von B', 'EUR', 1, 0, 1
              FROM document d WHERE d.id = ?
            """.trimIndent(),
            invoice,
            workspaceId,
            documentId,
        )
        TestDatabase.update("INSERT INTO invoice_draft (id, workspace_id, state, data) VALUES (?, ?, 'ISSUED', '{}')", invoice, workspaceId)
        TestDatabase.update(
            """
            INSERT INTO invoice_delivery (id, workspace_id, invoice_id, recipient, subject, message_id)
            VALUES (?, ?, ?, 'kunde@b.example', 'Rechnung B-1', ?)
            """.trimIndent(),
            delivery,
            workspaceId,
            invoice,
            "<$delivery@test>",
        )
        return invoice to delivery
    }

    private fun exerciseAsA(op: Operation) {
        val headers = mutableListOf<Pair<String, String>>()
        if (op.headerParams.contains("Idempotency-Key")) headers += "Idempotency-Key" to UUID.randomUUID().toString()
        if (op.headerParams.contains("If-Match")) {
            // Optimistic locking: use A's own current version, as a client would; for B's resources
            // any version (the answer must be 404 whatever the version says).
            if (op.pathParams.isEmpty()) {
                a.get("/api/v1${op.path}").header("ETag")?.let { headers += "If-Match" to it }
            } else {
                headers += "If-Match" to "\"0\""
            }
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
                    val json = ((body?.get("content") as Map<String, Any?>?)?.get("application/json")) as Map<String, Any?>?
                    val multipart = ((body?.get("content") as Map<String, Any?>?)?.containsKey("multipart/form-data")) == true
                    val example =
                        json?.get("example") ?: if (multipart) {
                            de.erika.support.MultipartFile
                                .png()
                        } else {
                            null
                        }
                    // JSON bodies need an example; multipart uploads (the logo) get a small PNG.
                    if (json != null && example == null) fail("$method $path: request bodies need an `example` for the isolation harness")
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
