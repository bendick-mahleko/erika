package de.erika.identity

import de.erika.platform.security.WorkspaceRole
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal enum class DevicePlatform { ANDROID, IOS }

/** Why a device was locked out (ADR 0037). */
internal enum class DeviceRevokedReason { OWNER, TOKEN_REUSE, SIGNED_OUT }

internal enum class TokenKind { ACCESS, REFRESH }

internal data class MobileDeviceRow(
    val id: UUID,
    val workspaceId: UUID,
    val userId: UUID,
    val displayName: String,
    /** The user's current role in the device's workspace; null when no longer a member. */
    val role: WorkspaceRole?,
    val name: String,
    val platform: DevicePlatform,
    val appVersion: String?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val lastSyncAt: Instant?,
    val revokedAt: Instant?,
    val revokedReason: DeviceRevokedReason?,
)

internal data class MobileTokenRow(
    val deviceId: UUID,
    val kind: TokenKind,
    val expiresAt: Instant,
    val usedAt: Instant?,
)

/** `mobile_device` and `mobile_token` (V13). Runs in the caller's transaction, scoped to the workspace. */
@Repository
internal class MobileDeviceRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        id: UUID,
        workspaceId: UUID,
        userId: UUID,
        name: String,
        platform: DevicePlatform,
        appVersion: String?,
    ) {
        jdbc
            .sql(
                "INSERT INTO mobile_device (id, workspace_id, user_id, name, platform, app_version) " +
                    "VALUES (:id, :ws, :user, :name, :platform, :version)",
            ).param("id", id)
            .param("ws", workspaceId)
            .param("user", userId)
            .param("name", name)
            .param("platform", platform.name)
            .param("version", appVersion)
            .update()
    }

    fun find(id: UUID): MobileDeviceRow? =
        jdbc
            .sql("$SELECT WHERE d.id = :id AND d.workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    /** Active devices first (most recently seen first), then revoked ones (newest first). */
    fun inCurrentWorkspace(): List<MobileDeviceRow> =
        jdbc
            .sql(
                "$SELECT WHERE d.workspace_id = current_workspace_id() " +
                    "ORDER BY d.revoked_at IS NOT NULL, COALESCE(d.revoked_at, d.last_seen_at) DESC, d.id",
            ).query(::map)
            .list()

    /** Locks the device out; false if it was already revoked. */
    fun revoke(
        id: UUID,
        by: UUID?,
        reason: DeviceRevokedReason,
    ): Boolean =
        jdbc
            .sql(
                "UPDATE mobile_device SET revoked_at = now(), revoked_by = :by, revoked_reason = :reason, push_token = NULL " +
                    "WHERE id = :id AND workspace_id = current_workspace_id() AND revoked_at IS NULL",
            ).param("id", id)
            .param("by", by)
            .param("reason", reason.name)
            .update() == 1

    /** Records that the device was used; written at most once a minute to keep requests cheap. */
    fun touch(
        id: UUID,
        appVersion: String?,
    ) {
        jdbc
            .sql(
                """
                UPDATE mobile_device SET last_seen_at = now(), app_version = COALESCE(:version, app_version)
                 WHERE id = :id AND workspace_id = current_workspace_id()
                   AND (last_seen_at < now() - interval '1 minute' OR app_version IS DISTINCT FROM COALESCE(:version, app_version))
                """.trimIndent(),
            ).param("id", id)
            .param("version", appVersion)
            .update()
    }

    /** Active devices of the current workspace with a push token, whose user is still a member. */
    fun pushTargets(): List<Pair<UUID, String>> =
        jdbc
            .sql(
                """
                SELECT d.id, d.push_token FROM mobile_device d
                  JOIN membership m ON m.user_id = d.user_id AND m.workspace_id = d.workspace_id
                 WHERE d.workspace_id = current_workspace_id() AND d.revoked_at IS NULL AND d.push_token IS NOT NULL
                 ORDER BY d.id
                """.trimIndent(),
            ).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }
            .list()

    fun setPushToken(
        id: UUID,
        token: String?,
    ) {
        jdbc
            .sql(
                "UPDATE mobile_device SET push_token = :token, push_token_updated_at = now() " +
                    "WHERE id = :id AND workspace_id = current_workspace_id()",
            ).param("id", id)
            .param("token", token)
            .update()
    }

    fun recordSync(id: UUID) {
        jdbc
            .sql("UPDATE mobile_device SET last_sync_at = now() WHERE id = :id AND workspace_id = current_workspace_id()")
            .param("id", id)
            .update()
    }

    fun insertToken(
        hash: ByteArray,
        workspaceId: UUID,
        deviceId: UUID,
        kind: TokenKind,
        expiresAt: Instant,
    ) {
        jdbc
            .sql(
                "INSERT INTO mobile_token (token_hash, workspace_id, device_id, kind, expires_at) " +
                    "VALUES (:hash, :ws, :device, :kind, :expires)",
            ).param("hash", hash)
            .param("ws", workspaceId)
            .param("device", deviceId)
            .param("kind", kind.name)
            .param("expires", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
            .update()
    }

    /** The workspace a token belongs to, before any workspace is set (SECURITY DEFINER function, V13). */
    fun tokenWorkspace(hash: ByteArray): UUID? =
        jdbc
            .sql("SELECT mobile_token_workspace(:hash)")
            .param("hash", hash)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    fun findToken(hash: ByteArray): MobileTokenRow? =
        jdbc
            .sql(
                "SELECT device_id, kind, expires_at, used_at FROM mobile_token " +
                    "WHERE token_hash = :hash AND workspace_id = current_workspace_id()",
            ).param("hash", hash)
            .query { rs, _ ->
                MobileTokenRow(
                    rs.getObject("device_id", UUID::class.java),
                    TokenKind.valueOf(rs.getString("kind")),
                    rs.instant("expires_at")!!,
                    rs.instant("used_at"),
                )
            }.optional()
            .orElse(null)

    /** Marks a refresh token as used; false if it already was (a concurrent or repeated use). */
    fun useRefreshToken(hash: ByteArray): Boolean =
        jdbc
            .sql(
                "UPDATE mobile_token SET used_at = now() " +
                    "WHERE token_hash = :hash AND workspace_id = current_workspace_id() AND kind = 'REFRESH' AND used_at IS NULL",
            ).param("hash", hash)
            .update() == 1

    /** Removes the device's expired tokens; used ones stay until they expire (reuse detection). */
    fun deleteExpiredTokens(deviceId: UUID) {
        jdbc
            .sql("DELETE FROM mobile_token WHERE device_id = :device AND workspace_id = current_workspace_id() AND expires_at < now()")
            .param("device", deviceId)
            .update()
    }

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = MobileDeviceRow(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        userId = rs.getObject("user_id", UUID::class.java),
        displayName = rs.getString("display_name"),
        role = rs.getString("role")?.let(WorkspaceRole::valueOf),
        name = rs.getString("name"),
        platform = DevicePlatform.valueOf(rs.getString("platform")),
        appVersion = rs.getString("app_version"),
        createdAt = rs.instant("created_at")!!,
        lastSeenAt = rs.instant("last_seen_at")!!,
        lastSyncAt = rs.instant("last_sync_at"),
        revokedAt = rs.instant("revoked_at"),
        revokedReason = rs.getString("revoked_reason")?.let(DeviceRevokedReason::valueOf),
    )

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private companion object {
        const val SELECT =
            "SELECT d.id, d.workspace_id, d.user_id, u.display_name, m.role, d.name, d.platform, d.app_version, " +
                "d.created_at, d.last_seen_at, d.last_sync_at, d.revoked_at, d.revoked_reason " +
                "FROM mobile_device d JOIN app_user u ON u.id = d.user_id " +
                "LEFT JOIN membership m ON m.user_id = d.user_id AND m.workspace_id = d.workspace_id"
    }
}
