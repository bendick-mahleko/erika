package de.erika.identity

import de.erika.support.ApiIntegrationTest
import de.erika.support.Phones
import de.erika.support.TestDatabase
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MARA Stage 3 (ADR 0037, M-AC-01, M-NF-07): a technician signs in on a phone once with
 * password and TOTP, works with bearer tokens, and a lost phone is locked out at once.
 */
class MobileDevicesIT : ApiIntegrationTest() {
    private val phones by lazy { Phones(this) }

    /** As the schema owner: the audit log is behind RLS. */
    private fun auditActions(workspaceId: UUID): List<String> =
        TestDatabase.asOwner { c ->
            c.prepareStatement("SELECT action FROM audit_log WHERE workspace_id = ? ORDER BY seq").use { ps ->
                ps.setObject(1, workspaceId)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    @Test
    fun `a technician sets up 2FA in the app, signs in once and works with tokens`() {
        val owner = client()
        accounts.enrolled("chefin", owner, workspaceName = "Sanitär Krause")
        val tech = phones.technician(owner)
        val phone = phones.phone()

        // Without 2FA the app must set it up first.
        val first =
            phone.post(
                "/api/v1/mobile/auth/device-login",
                mapOf("email" to tech.email, "password" to tech.password, "device" to phones.device()),
            )
        assertEquals(409, first.status, "$first")
        assertEquals("TOTP_ENROLMENT_REQUIRED", first.code)

        val enrolment = phone.post("/api/v1/mobile/auth/totp-enrolment", mapOf("email" to tech.email, "password" to tech.password))
        assertEquals(200, enrolment.status, "$enrolment")
        assertTrue(
            enrolment.tree
                .path("otpauthUri")
                .asString()
                .startsWith("otpauth://totp/"),
        )
        val secret = Base32.decode(enrolment.tree.path("secret").asString())

        val login =
            phone.post(
                "/api/v1/mobile/auth/device-login",
                mapOf(
                    "email" to tech.email,
                    "password" to tech.password,
                    "code" to accounts.nextCode(tech.email, secret),
                    "device" to phones.device("Galaxy XCover"),
                ),
            )
        assertEquals(200, login.status, "$login")
        assertNull(login.header("Set-Cookie"), "the app API sets no cookies")
        assertEquals(10, login.tree.path("recoveryCodes").size(), "recovery codes once, when 2FA was set up")
        assertEquals(
            "TECHNICIAN",
            login.tree
                .path("user")
                .path("role")
                .asString(),
        )
        assertEquals(
            "Sanitär Krause",
            login.tree
                .path("user")
                .path("workspaceName")
                .asString(),
        )
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

        val status = phone.get("/api/v1/mobile/devices/me/status", "X-App-Version" to "0.2.0")
        assertEquals(200, status.status, "$status")
        assertEquals(login.tree.path("deviceId").asString(), status.tree.path("deviceId").asString())
        assertEquals(
            tech.userId.toString(),
            status.tree
                .path("user")
                .path("userId")
                .asString(),
        )
        assertEquals("NONE", status.tree.path("appUpdate").asString())

        // The enrolment is done: a second set-up is refused, and a new phone needs a code.
        assertEquals(
            "TOTP_ALREADY_ENABLED",
            phone.post("/api/v1/mobile/auth/totp-enrolment", mapOf("email" to tech.email, "password" to tech.password)).code,
        )
        val actions = auditActions(tech.workspaceId)
        assertTrue("identity.totp.enrolled" in actions && "identity.device.registered" in actions, "$actions")

        // The owner sees the phone; the technician still has no web session.
        val list = owner.get("/api/v1/devices")
        assertEquals(200, list.status, "$list")
        val device =
            list.tree
                .path("items")
                .values()
                .single()
        assertEquals("Galaxy XCover", device.path("name").asString())
        assertEquals("TECHNICIAN", device.path("role").asString())
        assertTrue(device.path("revokedAt").isMissingNode)
    }

    @Test
    fun `sign-in without TOTP is refused, a wrong code too, and tax advisors can't use the app`() {
        val owner = client()
        val user = accounts.enrolled("mit-2fa", owner)
        val phone = phones.phone()
        val noCode =
            phone.post(
                "/api/v1/mobile/auth/device-login",
                mapOf("email" to user.email, "password" to user.password, "device" to phones.device()),
            )
        assertEquals(401, noCode.status, "$noCode")
        assertEquals("TOTP_REQUIRED", noCode.code)

        val wrongCode =
            phone.post(
                "/api/v1/mobile/auth/device-login",
                mapOf("email" to user.email, "password" to user.password, "code" to "000000", "device" to phones.device()),
            )
        assertEquals(401, wrongCode.status)
        assertEquals("INVALID_CODE", wrongCode.code)

        val wrongPassword =
            phone.post(
                "/api/v1/mobile/auth/device-login",
                mapOf(
                    "email" to user.email,
                    "password" to "falsch",
                    "code" to accounts.nextCode(user.email, user.totpSecret!!),
                    "device" to phones.device(),
                ),
            )
        assertEquals("INVALID_CREDENTIALS", wrongPassword.code)

        // A recovery code works instead of a TOTP code.
        val recovery =
            phone.post(
                "/api/v1/mobile/auth/device-login",
                mapOf(
                    "email" to user.email,
                    "password" to user.password,
                    "recoveryCode" to user.recoveryCodes.first(),
                    "device" to phones.device(),
                ),
            )
        assertEquals(200, recovery.status, "$recovery")
        assertEquals(
            "OWNER",
            recovery.tree
                .path("user")
                .path("role")
                .asString(),
        )
        assertTrue(recovery.tree.path("recoveryCodes").isMissingNode || recovery.tree.path("recoveryCodes").isNull)

        val (advisor, _) = accounts.invited(owner, "ADVISOR")
        val advisorLogin =
            phone.post(
                "/api/v1/mobile/auth/device-login",
                mapOf(
                    "email" to advisor.email,
                    "password" to advisor.password,
                    "code" to accounts.nextCode(advisor.email, advisor.totpSecret!!),
                    "device" to phones.device(),
                ),
            )
        assertEquals(403, advisorLogin.status, "$advisorLogin")
        assertEquals("APP_NOT_FOR_ROLE", advisorLogin.code)
    }

    @Test
    fun `refresh tokens rotate and reusing one revokes the device`() {
        val owner = client()
        accounts.enrolled("rotation", owner)
        val (phone, tech) = phones.signIn(phones.technician(owner))
        val firstRefresh = phone.refreshToken
        val firstAccess = phone.accessToken

        val renewed = phone.refresh()
        assertEquals(200, renewed.status, "$renewed")
        assertNotEquals(firstRefresh, phone.refreshToken)
        assertNotEquals(firstAccess, phone.accessToken)
        assertEquals(200, phone.get("/api/v1/mobile/devices/me/status").status)

        // Someone copied the old refresh token and uses it: the whole device is locked out.
        val thief = phones.phone()
        val stolen = thief.post("/api/v1/mobile/auth/refresh", mapOf("refreshToken" to firstRefresh))
        assertEquals(401, stolen.status, "$stolen")
        assertEquals("DEVICE_REVOKED", stolen.code)

        val after = phone.get("/api/v1/mobile/devices/me/status")
        assertEquals(401, after.status)
        assertEquals("DEVICE_REVOKED", after.code, "the legitimate phone is out too")
        assertEquals("DEVICE_REVOKED", phone.refresh().code)
        assertTrue("identity.device.revoked" in auditActions(tech.workspaceId))
        val device =
            owner
                .get("/api/v1/devices")
                .tree
                .path("items")
                .values()
                .single()
        assertEquals("TOKEN_REUSE", device.path("revokedReason").asString())

        assertEquals("REFRESH_TOKEN_INVALID", thief.post("/api/v1/mobile/auth/refresh", mapOf("refreshToken" to "x".repeat(43))).code)
    }

    @Test
    fun `a device revoked by the owner is refused at once, and only the owner may revoke`() {
        val owner = client()
        accounts.enrolled("verloren", owner)
        val (phone, _) = phones.signIn(phones.technician(owner))
        val (_, memberClient) = accounts.invited(owner, "MEMBER")

        assertEquals(403, memberClient.get("/api/v1/devices").status, "the device list is the owner's")
        assertEquals(403, memberClient.post("/api/v1/devices/${phone.deviceId}/revoke").status)
        assertEquals(404, owner.post("/api/v1/devices/${UUID.randomUUID()}/revoke").status)

        val revoked = owner.post("/api/v1/devices/${phone.deviceId}/revoke")
        assertEquals(200, revoked.status, "$revoked")
        assertEquals("OWNER", revoked.tree.path("revokedReason").asString())
        assertEquals(200, owner.post("/api/v1/devices/${phone.deviceId}/revoke").status, "revoking twice changes nothing")

        val next = phone.get("/api/v1/mobile/devices/me/status")
        assertEquals(401, next.status)
        assertEquals("DEVICE_REVOKED", next.code)
        assertEquals("DEVICE_REVOKED", phone.refresh().code)
    }

    @Test
    fun `expired access tokens, sign-out, removed members and old apps`() {
        val owner = client()
        accounts.enrolled("ablauf", owner)
        val tech = phones.technician(owner)
        val (phone, enrolledTech) = phones.signIn(tech)

        TestDatabase.update(
            "UPDATE mobile_token SET expires_at = now() - interval '1 second' WHERE device_id = ?::uuid AND kind = 'ACCESS'",
            phone.deviceId,
        )
        val expired = phone.get("/api/v1/mobile/devices/me/status")
        assertEquals(401, expired.status)
        assertEquals("TOKEN_EXPIRED", expired.code)
        assertEquals(200, phone.refresh().status)
        assertEquals(200, phone.get("/api/v1/mobile/devices/me/status").status)

        // A refresh token is no access token, and garbage is no token.
        val wrongKind = phones.phone().apply { accessToken = phone.refreshToken }
        assertEquals("UNAUTHORIZED", wrongKind.get("/api/v1/mobile/devices/me/status").code)
        assertEquals(
            401,
            phones
                .phone()
                .apply { accessToken = "kein-token" }
                .get("/api/v1/mobile/devices/me/status")
                .status,
        )
        assertEquals(401, phones.phone().get("/api/v1/mobile/devices/me/status").status, "no token")

        // An app below the minimum version may only check its status and sign out.
        val old = phone.post("/api/v1/mobile/auth/refresh", mapOf("refreshToken" to phone.refreshToken), "X-App-Version" to "0.0.1")
        assertEquals(426, old.status, "$old")
        assertEquals("APP_UPDATE_REQUIRED", old.code)
        assertEquals(
            "REQUIRED",
            phone
                .get("/api/v1/mobile/devices/me/status", "X-App-Version" to "0.0.1")
                .tree
                .path("appUpdate")
                .asString(),
        )

        // Sign-out revokes the device.
        assertEquals(204, phone.post("/api/v1/mobile/auth/logout").status)
        assertEquals("DEVICE_REVOKED", phone.get("/api/v1/mobile/devices/me/status").code)

        // A member who is removed from the workspace is locked out on every phone.
        val (second, _) = phones.signIn(enrolledTech)
        assertEquals(200, second.get("/api/v1/mobile/devices/me/status").status)
        val memberId =
            owner
                .get("/api/v1/members")
                .tree
                .path("items")
                .values()
                .single { it.path("email").asString() == tech.email }
                .path("id")
                .asString()
        val removed = owner.send("DELETE", "/api/v1/members/$memberId", null)
        assertTrue(removed.status in 200..204, "$removed")
        assertEquals("DEVICE_REVOKED", second.get("/api/v1/mobile/devices/me/status").code)
    }
}
