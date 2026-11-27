package de.erika.sync.web

import de.erika.contract.mobile.api.MobileApi
import de.erika.contract.mobile.model.AppUpdate
import de.erika.contract.mobile.model.DeviceStatus
import de.erika.contract.mobile.model.DeviceUser
import de.erika.contract.mobile.model.MobileRole
import de.erika.contract.mobile.model.MobileStatus
import de.erika.contract.mobile.model.ProtocolVersions
import de.erika.contract.mobile.model.PushToken
import de.erika.contract.mobile.model.TelemetryReport
import de.erika.identity.MobileDevices
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.web.ErikaException
import de.erika.sync.MobileProperties
import de.erika.sync.MobileTelemetry
import de.erika.sync.SyncProtocol
import de.erika.sync.updateAdvice
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock
import java.time.OffsetDateTime

/**
 * `GET /mobile/status` (api/mobile.yaml): answers without a token, before sign-in.
 * `GET /mobile/devices/me/status`: the signed-in device's holder, and whether the app may continue.
 */
@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class MobileStatusController(
    private val props: MobileProperties,
    private val devices: MobileDevices,
    private val telemetry: MobileTelemetry,
    private val clock: Clock = Clock.systemUTC(),
) : MobileApi {
    /** Content-free counters per day (MARA Stage 9 field test). */
    override fun putTelemetry(telemetryReport: TelemetryReport): ResponseEntity<Unit> {
        val p = ErikaPrincipal.current()
        val days =
            telemetryReport.days.associate { d ->
                val c = d.counters
                d.day to
                    mapOf(
                        "syncDone" to c.syncDone,
                        "syncOffline" to c.syncOffline,
                        "syncFailed" to c.syncFailed,
                        "syncReadOnly" to c.syncReadOnly,
                        "pushApplied" to c.pushApplied,
                        "pushConflict" to c.pushConflict,
                        "pushRejected" to c.pushRejected,
                        "uploadDone" to c.uploadDone,
                        "uploadOffline" to c.uploadOffline,
                        "uploadChunkRetries" to c.uploadChunkRetries,
                        "uploadRestarts" to c.uploadRestarts,
                        "outboxMax" to c.outboxMax,
                    ).mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
            }
        telemetry.save(p.workspaceId, checkNotNull(p.deviceId) { "device request without a device" }, days)
        return ResponseEntity.noContent().build()
    }

    override fun setPushToken(pushToken: PushToken): ResponseEntity<Unit> {
        devices.setPushToken(checkNotNull(ErikaPrincipal.current().deviceId), pushToken.token.trim())
        return ResponseEntity.noContent().build()
    }

    override fun getDeviceStatus(xAppVersion: String?): ResponseEntity<DeviceStatus> {
        val principal = ErikaPrincipal.current()
        val deviceId = checkNotNull(principal.deviceId) { "device request without a device" }
        // The token filter already refused revoked devices; this covers a revocation in between.
        val holder =
            devices.holder(deviceId)
                ?: throw ErikaException("DEVICE_REVOKED", HttpStatus.UNAUTHORIZED, "This device has been signed out.")
        return ResponseEntity.ok(
            DeviceStatus(
                deviceId = deviceId,
                user =
                    DeviceUser(
                        userId = holder.userId,
                        displayName = holder.displayName,
                        role = MobileRole.valueOf(holder.role.name),
                        workspaceName = holder.workspaceName,
                    ),
                protocol = ProtocolVersions(current = SyncProtocol.CURRENT, minimum = SyncProtocol.MINIMUM),
                minAppVersion = props.minAppVersion,
                appUpdate = AppUpdate.valueOf(updateAdvice(xAppVersion, props).name),
                serverTime = OffsetDateTime.now(clock),
            ),
        )
    }

    override fun getMobileStatus(xAppVersion: String?): ResponseEntity<MobileStatus> =
        ResponseEntity.ok(
            MobileStatus(
                protocol = ProtocolVersions(current = SyncProtocol.CURRENT, minimum = SyncProtocol.MINIMUM),
                minAppVersion = props.minAppVersion,
                recommendedAppVersion = props.recommendedAppVersion,
                appUpdate = AppUpdate.valueOf(updateAdvice(xAppVersion, props).name),
                serverTime = OffsetDateTime.now(clock),
            ),
        )
}

/**
 * Forced update (ADR 0038): an app below `erika.mobile.min-app-version` gets
 * `426 APP_UPDATE_REQUIRED` on every MARA request except the two status checks and sign-out,
 * so it can still tell the user why and sign out cleanly. Apps that send no version are let
 * through (the status check asks them).
 */
@Component
@Profile(RuntimeInstance.PROFILE_API)
internal class AppVersionInterceptor(
    private val props: MobileProperties,
) : HandlerInterceptor {
    override fun preHandle(
        request: jakarta.servlet.http.HttpServletRequest,
        response: jakarta.servlet.http.HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (request.requestURI in EXEMPT) return true
        if (updateAdvice(request.getHeader("X-App-Version"), props) == de.erika.sync.UpdateAdvice.REQUIRED) {
            throw ErikaException("APP_UPDATE_REQUIRED", HttpStatus.UPGRADE_REQUIRED, "Please update the MARA app.")
        }
        return true
    }

    private companion object {
        val EXEMPT = setOf("/api/v1/mobile/status", "/api/v1/mobile/devices/me/status", "/api/v1/mobile/auth/logout")
    }
}

@Configuration(proxyBeanMethods = false)
@Profile(RuntimeInstance.PROFILE_API)
internal class AppVersionConfiguration(
    private val interceptor: AppVersionInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/mobile/**")
    }
}
