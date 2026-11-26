package de.erika.identity.web

import de.erika.contract.api.DevicesApi
import de.erika.contract.mobile.api.MobileAuthApi
import de.erika.contract.mobile.model.Credentials
import de.erika.contract.mobile.model.DeviceLogin
import de.erika.contract.mobile.model.DeviceSession
import de.erika.contract.mobile.model.DeviceTokens
import de.erika.contract.mobile.model.DeviceUser
import de.erika.contract.mobile.model.MobileRole
import de.erika.contract.mobile.model.MobileTotpEnrolment
import de.erika.contract.mobile.model.RefreshRequest
import de.erika.contract.model.Device
import de.erika.contract.model.DeviceList
import de.erika.contract.model.DevicePlatform
import de.erika.contract.model.DeviceRevokedReason
import de.erika.contract.model.Role
import de.erika.identity.DeviceRegistration
import de.erika.identity.IssuedTokens
import de.erika.identity.MobileAuth
import de.erika.identity.MobileDeviceRow
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import de.erika.identity.DevicePlatform as Platform

/** Device sign-in of the MARA app (api/mobile.yaml, tag `mobile-auth`, ADR 0037). */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class MobileAuthController(
    private val auth: MobileAuth,
    private val request: HttpServletRequest,
) : MobileAuthApi {
    override fun startMobileTotpEnrolment(credentials: Credentials): ResponseEntity<MobileTotpEnrolment> {
        val (secret, uri) = auth.startEnrolment(credentials.email, credentials.password, clientKey())
        return ResponseEntity.ok(MobileTotpEnrolment(secret = secret, otpauthUri = uri))
    }

    override fun deviceLogin(
        deviceLogin: DeviceLogin,
        xAppVersion: String?,
    ): ResponseEntity<DeviceSession> {
        val signedIn =
            auth.signIn(
                deviceLogin.email,
                deviceLogin.password,
                deviceLogin.code,
                deviceLogin.recoveryCode,
                DeviceRegistration(
                    deviceLogin.device.name,
                    Platform.valueOf(deviceLogin.device.platform.name),
                    deviceLogin.device.appVersion.ifBlank { xAppVersion },
                ),
                clientKey(),
            )
        return ResponseEntity.ok(
            DeviceSession(
                deviceId = signedIn.deviceId,
                tokens = signedIn.tokens.toApi(),
                user =
                    DeviceUser(
                        userId = signedIn.holder.userId,
                        displayName = signedIn.holder.displayName,
                        role = MobileRole.valueOf(signedIn.holder.role.name),
                        workspaceName = signedIn.holder.workspaceName,
                    ),
                recoveryCodes = signedIn.recoveryCodes,
            ),
        )
    }

    override fun refreshDeviceTokens(
        refreshRequest: RefreshRequest,
        xAppVersion: String?,
    ): ResponseEntity<DeviceTokens> = ResponseEntity.ok(auth.refresh(refreshRequest.refreshToken, xAppVersion).toApi())

    override fun deviceLogout(): ResponseEntity<Unit> {
        auth.signOut(ErikaPrincipal.current())
        return ResponseEntity.noContent().build()
    }

    private fun clientKey(): String = request.remoteAddr ?: "unknown"

    private fun IssuedTokens.toApi() =
        DeviceTokens(
            accessToken = accessToken,
            accessTokenExpiresAt = accessTokenExpiresAt.utc(),
            refreshToken = refreshToken,
            refreshTokenExpiresAt = refreshTokenExpiresAt.utc(),
        )
}

/** The owner's list of signed-in phones and revocation (openapi.yaml, tag `devices`, M-NF-07). */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class DevicesController(
    private val auth: MobileAuth,
) : DevicesApi {
    @PreAuthorize("hasRole('OWNER')")
    override fun listDevices(): ResponseEntity<DeviceList> = ResponseEntity.ok(DeviceList(items = auth.list().map { it.toApi() }))

    @PreAuthorize("hasRole('OWNER')")
    override fun revokeDevice(deviceId: UUID): ResponseEntity<Device> =
        ResponseEntity.ok(auth.revokeByOwner(deviceId, ErikaPrincipal.current().userId).toApi())

    private fun MobileDeviceRow.toApi() =
        Device(
            id = id,
            userId = userId,
            displayName = displayName,
            role = role?.let { Role.valueOf(it.name) },
            name = name,
            platform = DevicePlatform.valueOf(platform.name),
            appVersion = appVersion,
            createdAt = createdAt.utc(),
            lastSeenAt = lastSeenAt.utc(),
            lastSyncAt = lastSyncAt?.utc(),
            revokedAt = revokedAt?.utc(),
            revokedReason = revokedReason?.let { DeviceRevokedReason.valueOf(it.name) },
        )
}

private fun Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
