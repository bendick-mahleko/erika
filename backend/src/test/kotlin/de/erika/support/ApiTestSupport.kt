package de.erika.support

import de.erika.identity.Base32
import de.erika.identity.Totp
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base for API integration tests: the `api` role on a random port against the shared
 * PostgreSQL/S3/Mailpit containers, called over real HTTP (cookies, CSRF, sessions).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
@ActiveProfiles("api", "test")
abstract class ApiIntegrationTest {
    @LocalServerPort
    var port: Int = 0

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var jdbc: JdbcClient

    protected val json: JsonMapper = JsonMapper.builder().build()

    fun client() = TestClient("http://localhost:$port", json)

    val accounts by lazy { Accounts(this) }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun containers(registry: DynamicPropertyRegistry) = TestInfrastructure.register(registry)
    }
}

class Response(
    val status: Int,
    val body: String,
    val headers: java.net.http.HttpHeaders,
    private val json: JsonMapper,
) {
    val tree: JsonNode by lazy { json.readTree(body.ifBlank { "{}" }) }

    fun header(name: String): String? = headers.firstValue(name).orElse(null)

    val code: String get() = tree.path("code").asString()

    override fun toString() = "HTTP $status $body"
}

/** One browser: keeps cookies (session, CSRF, device) and sends the CSRF header like the front end. */
class TestClient(
    private val baseUrl: String,
    private val json: JsonMapper,
    /** Simulated client IP (X-Forwarded-For), so rate limits don't interfere between tests. */
    val ip: String = nextIp(),
) {
    val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val http = HttpClient.newBuilder().cookieHandler(cookies).build()

    fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ) = send("GET", path, null, headers)

    fun post(
        path: String,
        body: Any? = null,
        vararg headers: Pair<String, String>,
    ) = send("POST", path, body, headers)

    fun put(
        path: String,
        body: Any?,
        vararg headers: Pair<String, String>,
    ) = send("PUT", path, body, headers)

    fun send(
        method: String,
        path: String,
        body: Any?,
        headers: Array<out Pair<String, String>> = emptyArray(),
        csrf: Boolean = true,
    ): Response {
        if (csrf && method != "GET" && cookie("XSRF-TOKEN") == null) get("/api/v1/auth/session")
        val builder =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path"))
                .header("X-Forwarded-For", ip)
                .header("User-Agent", "ERIKA test client")
        if (csrf) cookie("XSRF-TOKEN")?.let { builder.header("X-XSRF-TOKEN", it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher =
            if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                builder.header("Content-Type", "application/json")
                HttpRequest.BodyPublishers.ofString(if (body is String) body else json.writeValueAsString(body))
            }
        val response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body(), response.headers(), json)
    }

    fun cookie(name: String): String? =
        cookies.cookieStore.cookies
            .firstOrNull { it.name == name }
            ?.value

    fun setCookie(
        name: String,
        value: String,
    ) {
        cookies.cookieStore.add(
            URI.create(baseUrl),
            HttpCookie(name, value).apply {
                path = "/"
                version = 0 // RFC 6265 style; version 1 would make the client quote all cookie values
            },
        )
    }

    companion object {
        private val counter = AtomicInteger(1)

        fun nextIp(): String = counter.getAndIncrement().let { "10.${(it shr 16) and 255}.${(it shr 8) and 255}.${it and 255}" }
    }
}

/** A user that can sign in, with its authenticator secret. */
data class TestUser(
    val email: String,
    val password: String,
    val userId: UUID,
    val workspaceId: UUID,
    val totpSecret: ByteArray?,
    val recoveryCodes: List<String> = emptyList(),
)

/** Creates accounts through the real API (sign-up → email link → password → TOTP enrolment). */
class Accounts(
    private val test: ApiIntegrationTest,
) {
    private val totp = Totp()
    private val lastStep = mutableMapOf<String, Long>()

    /** Signs up and verifies; returns the user without 2FA. */
    fun registered(
        prefix: String = "user",
        workspaceName: String = "Workspace $prefix",
    ): TestUser {
        val email = "$prefix-${UUID.randomUUID().toString().take(8)}@example.test"
        val password = "Passphrase for $prefix ${UUID.randomUUID()}"
        val client = test.client()
        val signup =
            client.post(
                "/api/v1/auth/signup",
                mapOf(
                    "email" to email,
                    "password" to password,
                    "displayName" to "Test $prefix",
                    "workspaceName" to workspaceName,
                    "termsAccepted" to true,
                ),
            )
        check(signup.status == 202) { "sign-up failed: $signup" }
        val token = Mails.verificationToken(test.jdbc, email)
        check(client.post("/api/v1/auth/verify-email", mapOf("token" to token)).status == 204)
        val (userId, workspaceId) =
            TestDatabase.asOwner {
                it
                    .prepareStatement("SELECT u.id, m.workspace_id FROM app_user u JOIN membership m ON m.user_id = u.id WHERE u.email = ?")
                    .apply { setString(1, email) }
                    .executeQuery()
                    .run {
                        next()
                        getObject(1, UUID::class.java) to getObject(2, UUID::class.java)
                    }
            }
        return TestUser(email, password, userId, workspaceId, null)
    }

    /** Registered user with 2FA set up; [client] ends up signed in. */
    fun enrolled(
        prefix: String = "user",
        client: TestClient = test.client(),
        workspaceName: String = "Workspace $prefix",
    ): TestUser {
        val user = registered(prefix, workspaceName)
        val login = client.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        check(login.status == 200 && login.tree.path("state").asString() == "TOTP_ENROLMENT_REQUIRED") { "login: $login" }
        val enrolment = client.post("/api/v1/auth/totp/enrolment")
        check(enrolment.status == 200) { "enrolment: $enrolment" }
        val secret = Base32.decode(enrolment.tree.path("secret").asString())
        val confirm = client.post("/api/v1/auth/totp/enrolment/confirm", mapOf("code" to nextCode(user.email, secret)))
        check(confirm.status == 200) { "confirm: $confirm" }
        return user.copy(
            totpSecret = secret,
            recoveryCodes =
                confirm.tree
                    .path("codes")
                    .values()
                    .map { it.asString() },
        )
    }

    /** Full sign-in of an enrolled user with [client]. */
    fun signIn(
        user: TestUser,
        client: TestClient = test.client(),
    ): TestClient {
        val login = client.post("/api/v1/auth/login", mapOf("email" to user.email, "password" to user.password))
        check(login.status == 200) { "login: $login" }
        val second = client.post("/api/v1/auth/totp", mapOf("code" to nextCode(user.email, user.totpSecret!!)))
        check(second.status == 200 && second.tree.path("state").asString() == "AUTHENTICATED") { "totp: $second" }
        return client
    }

    /**
     * A TOTP code that the server will accept: codes are single-use per 30 s step, so
     * repeated sign-ins of one user in a test use the next step (allowed: ±1 step).
     * Waits for the next step if both are used up.
     */
    @Synchronized
    fun nextCode(
        key: String,
        secret: ByteArray,
    ): String {
        while (true) {
            val now = totp.currentStep()
            val step = maxOf(now - 1, (lastStep[key] ?: Long.MIN_VALUE) + 1)
            if (step <= now + 1) {
                lastStep[key] = step
                return totp.code(secret, step)
            }
            Thread.sleep(1000)
        }
    }
}

/** Reads queued mails from the job table (the API queues them; the worker would send them). */
object Mails {
    private val TOKEN = Regex("token=([A-Za-z0-9_-]+)")

    fun queuedTo(
        jdbc: JdbcClient,
        email: String,
    ): List<Map<String, String>> =
        jdbc
            .sql("SELECT payload::text FROM job WHERE type = 'platform.send-mail' AND payload->>'to' = :to ORDER BY created_at")
            .param("to", email)
            .query(String::class.java)
            .list()
            .map {
                @Suppress("UNCHECKED_CAST")
                JsonMapper.builder().build().readValue(it, Map::class.java) as Map<String, String>
            }

    fun verificationToken(
        jdbc: JdbcClient,
        email: String,
    ): String {
        val body = queuedTo(jdbc, email).lastOrNull()?.get("body") ?: error("no mail queued for $email")
        return TOKEN.find(body)?.groupValues?.get(1) ?: error("no token in mail")
    }
}

/** Direct database access as the schema owner (not subject to RLS), for setup and tampering. */
object TestDatabase {
    fun <T> asOwner(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(TestInfrastructure.jdbcUrl, "erika_owner", "erika_owner_dev").use(block)

    fun <T> asApp(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(TestInfrastructure.jdbcUrl, "erika_app", "erika_app_dev").use(block)

    /**
     * Removes the stored_object rows that tests wrote without a real object in storage
     * (marked with size_bytes = 1), and what refers to them, so the archive integrity check
     * sees only genuine originals.
     */
    fun removeForgedOriginals() {
        val forged =
            "SELECT d.id FROM document d JOIN stored_object s ON s.workspace_id = d.workspace_id AND s.sha256 = d.sha256 " +
                "WHERE s.size_bytes = 1"
        // Issued invoices written directly by tests (tenant-isolation fixtures) on forged documents.
        val invoices = "SELECT id FROM invoice WHERE document_id IN ($forged)"
        update("DELETE FROM invoice_delivery WHERE invoice_id IN ($invoices)")
        asOwner { c ->
            // Issued drafts are frozen by a trigger; the schema owner lifts it for this cleanup only.
            c.createStatement().use { st ->
                st.execute("ALTER TABLE invoice_draft DISABLE TRIGGER invoice_draft_frozen")
                try {
                    st.executeUpdate("DELETE FROM invoice_draft WHERE id IN ($invoices) OR original_invoice_id IN ($invoices)")
                } finally {
                    st.execute("ALTER TABLE invoice_draft ENABLE TRIGGER invoice_draft_frozen")
                }
            }
        }
        update("DELETE FROM invoice WHERE id IN ($invoices)")
        update("DELETE FROM validation_result WHERE document_id IN ($forged)")
        update("DELETE FROM status_event WHERE document_id IN ($forged)")
        update("DELETE FROM derived_object WHERE document_id IN ($forged)")
        update("DELETE FROM parsed_invoice WHERE document_id IN ($forged)")
        update("UPDATE document SET duplicate_of = NULL WHERE duplicate_of IN ($forged)")
        update("DELETE FROM document WHERE id IN ($forged)")
        update("DELETE FROM stored_object WHERE size_bytes = 1")
    }

    fun update(
        sql: String,
        vararg args: Any?,
    ): Int =
        asOwner { c ->
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeUpdate()
            }
        }
}
