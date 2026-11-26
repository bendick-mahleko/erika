package de.erika.identity

import de.erika.platform.security.WorkspaceRole
import org.springframework.stereotype.Service
import java.util.UUID

/** Who a registered phone belongs to (MARA app, ADR 0037). */
data class DeviceHolder(
    val userId: UUID,
    val displayName: String,
    val role: WorkspaceRole,
    val workspaceName: String,
)

/**
 * Published API of the `identity` module for MARA's devices. Runs in the caller's
 * transaction, which must be scoped to the device's workspace.
 */
@Service
class MobileDevices internal constructor(
    private val devices: MobileDeviceRepository,
    private val workspaces: WorkspaceRepository,
) {
    /** The holder of an active device, or null if it doesn't exist, is revoked or its user left the workspace. */
    fun holder(deviceId: UUID): DeviceHolder? {
        val device = devices.find(deviceId) ?: return null
        val role = device.role ?: return null
        if (device.revokedAt != null) return null
        val workspace = workspaces.find(device.workspaceId) ?: return null
        return DeviceHolder(device.userId, device.displayName, role, workspace.name)
    }

    /** Active phones of the current workspace that registered a push token. */
    fun pushTargets(): List<PushTarget> = devices.pushTargets().map { (id, token) -> PushTarget(id, token) }

    fun setPushToken(
        deviceId: UUID,
        token: String,
    ) = devices.setPushToken(deviceId, token)

    /** The push service no longer knows the token (app uninstalled). */
    fun clearPushToken(deviceId: UUID) = devices.setPushToken(deviceId, null)

    /** For the owner's device list: when the phone last synced. */
    fun recordSync(deviceId: UUID) = devices.recordSync(deviceId)
}

/** A phone to send a data-only "sync now" message to. */
data class PushTarget(
    val deviceId: UUID,
    val token: String,
)
