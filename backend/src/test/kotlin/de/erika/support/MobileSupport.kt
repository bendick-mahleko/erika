package de.erika.support

import de.erika.identity.Base32
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * A phone running the MARA app: no cookies, no CSRF; sends `Authorization: Bearer` once
 * signed in (ADR 0037).
 */
class MobileClient(
    private val baseUrl: String,
    private val json: JsonMapper,
    val ip: String = TestClient.nextIp(),
    /** Per-request timeout, for tests through a faulty network. */
    private val timeout: java.time.Duration? = null,
) {
    private val http = HttpClient.newHttpClient()
    var accessToken: String? = null
    var refreshToken: String? = null
    var deviceId: String? = null

    fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ) = send("GET", path, null, headers)

    fun post(
        path: String,
        body: Any? = null,
        vararg headers: Pair<String, String>,
    ) = send("POST", path, body, headers)

    fun send(
        method: String,
        path: String,
        body: Any?,
        headers: Array<out Pair<String, String>> = emptyArray(),
    ): Response {
        val builder =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path"))
                .header("X-Forwarded-For", ip)
                .header("User-Agent", "MARA test app")
        timeout?.let { builder.timeout(it) }
        accessToken?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher =
            if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else if (body is ByteArray) {
                // Raw bytes (tus chunks); the caller sends the Content-Type.
                HttpRequest.BodyPublishers.ofByteArray(body)
            } else {
                builder.header("Content-Type", "application/json")
                HttpRequest.BodyPublishers.ofString(if (body is String) body else json.writeValueAsString(body))
            }
        val response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body(), response.headers(), json)
    }

    /** Renews the tokens with the stored refresh token (rotation). */
    fun refresh(): Response {
        val r = post("/api/v1/mobile/auth/refresh", mapOf("refreshToken" to refreshToken))
        if (r.status == 200) {
            accessToken = r.tree.path("accessToken").asString()
            refreshToken = r.tree.path("refreshToken").asString()
        }
        return r
    }
}

/** Technicians and their phones, through the real API. */
class Phones(
    private val test: ApiIntegrationTest,
) {
    fun phone() = MobileClient("http://localhost:${test.port}", JsonMapper.builder().build())

    /** Invited by [owner] as TECHNICIAN, signed up and verified; no 2FA yet (technicians can't use the web). */
    fun technician(
        owner: TestClient,
        prefix: String = "techniker",
    ): TestUser = invitedWithout2fa(owner, "TECHNICIAN", prefix)

    fun invitedWithout2fa(
        owner: TestClient,
        role: String,
        prefix: String,
    ): TestUser {
        val email = "$prefix-${UUID.randomUUID().toString().take(8)}@example.test"
        val invite =
            owner.post(
                "/api/v1/invitations",
                mapOf("email" to email, "role" to role),
                "Idempotency-Key" to UUID.randomUUID().toString(),
            )
        check(invite.status == 201) { "invitation: $invite" }
        val password = "Passphrase for $prefix ${UUID.randomUUID()}"
        val client = test.client()
        val signup =
            client.post(
                "/api/v1/auth/signup",
                mapOf(
                    "email" to email,
                    "password" to password,
                    "displayName" to "Test $prefix",
                    "termsAccepted" to true,
                    "workspaceInvitation" to Mails.verificationToken(test.jdbc, email),
                ),
            )
        check(signup.status == 202) { "sign-up: $signup" }
        check(client.post("/api/v1/auth/verify-email", mapOf("token" to Mails.verificationToken(test.jdbc, email))).status == 204)
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

    fun device(name: String = "Pixel 7") = mapOf("name" to name, "platform" to "ANDROID", "appVersion" to "0.2.0")

    /**
     * Signs [user] in on a new phone. Without 2FA, sets it up from the app first (as a
     * technician does). Returns the phone and the user with the authenticator secret.
     */
    fun signIn(
        user: TestUser,
        phone: MobileClient = phone(),
    ): Pair<MobileClient, TestUser> {
        var current = user
        if (current.totpSecret == null) {
            val enrolment =
                phone.post("/api/v1/mobile/auth/totp-enrolment", mapOf("email" to user.email, "password" to user.password))
            check(enrolment.status == 200) { "enrolment: $enrolment" }
            current = current.copy(totpSecret = Base32.decode(enrolment.tree.path("secret").asString()))
        }
        val login =
            phone.post(
                "/api/v1/mobile/auth/device-login",
                mapOf(
                    "email" to current.email,
                    "password" to current.password,
                    "code" to test.accounts.nextCode(current.email, current.totpSecret!!),
                    "device" to device(),
                ),
            )
        check(login.status == 200) { "device login: $login" }
        phone.accessToken =
            login.tree
                .path("tokens")
                .path("accessToken")
                .asString()
        phone.refreshToken =
            login.tree
                .path("tokens")
                .path("refreshToken")
                .asString()
        phone.deviceId = login.tree.path("deviceId").asString()
        return phone to current
    }
}
