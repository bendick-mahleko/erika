package de.erika.identity

import de.erika.platform.security.WorkspaceRole
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

internal data class UserRow(
    val id: UUID,
    val email: String,
    val displayName: String,
    val passwordHash: String,
    val locale: String,
    val emailVerifiedAt: Instant?,
    val totpSecret: String?,
    val totpEnabledAt: Instant?,
    val totpLastStep: Long?,
    val lockedUntil: Instant?,
) {
    val totpEnabled get() = totpEnabledAt != null

    fun isLocked(now: Instant) = lockedUntil?.isAfter(now) == true
}

/** Users are global (not tenant-scoped): one person can belong to several workspaces. */
@Repository
internal class UserRepository(
    private val jdbc: JdbcClient,
) {
    fun findByEmail(email: String): UserRow? =
        jdbc
            .sql("$SELECT WHERE lower(email) = lower(:email)")
            .param("email", email.trim())
            .query(::map)
            .optional()
            .orElse(null)

    fun findById(id: UUID): UserRow? =
        jdbc
            .sql("$SELECT WHERE id = :id")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    fun insert(
        id: UUID,
        email: String,
        displayName: String,
        passwordHash: String,
        locale: String,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO app_user (id, email, display_name, password_hash, locale)
                VALUES (:id, :email, :displayName, :passwordHash, :locale)
                """.trimIndent(),
            ).param("id", id)
            .param("email", email.trim())
            .param("displayName", displayName.trim())
            .param("passwordHash", passwordHash)
            .param("locale", locale)
            .update()
    }

    fun markEmailVerified(id: UUID) {
        jdbc.sql("UPDATE app_user SET email_verified_at = coalesce(email_verified_at, now()) WHERE id = :id").param("id", id).update()
    }

    /**
     * Counts a failed attempt. At [maxFailures] the account is locked for [lockFor] and the
     * counter starts again. Returns true if this attempt locked the account.
     */
    fun recordFailure(
        id: UUID,
        maxFailures: Int,
        lockFor: Duration,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE app_user
                   SET locked_until  = CASE WHEN failed_logins + 1 >= :max
                                            THEN now() + make_interval(secs => :lockSeconds) ELSE locked_until END,
                       failed_logins = CASE WHEN failed_logins + 1 >= :max THEN 0 ELSE failed_logins + 1 END
                 WHERE id = :id
                RETURNING failed_logins = 0 AS just_locked
                """.trimIndent(),
            ).param("id", id)
            .param("max", maxFailures)
            .param("lockSeconds", lockFor.toSeconds())
            .query(Boolean::class.java)
            .single()

    fun resetFailures(id: UUID) {
        jdbc.sql("UPDATE app_user SET failed_logins = 0, locked_until = NULL WHERE id = :id").param("id", id).update()
    }

    /** Stores a not-yet-confirmed TOTP secret (encrypted). Only while 2FA is off. */
    fun setPendingTotpSecret(
        id: UUID,
        encrypted: String,
    ): Boolean =
        jdbc
            .sql("UPDATE app_user SET totp_secret = :secret WHERE id = :id AND totp_enabled_at IS NULL")
            .param("id", id)
            .param("secret", encrypted)
            .update() == 1

    fun enableTotp(
        id: UUID,
        step: Long,
    ): Boolean =
        jdbc
            .sql("UPDATE app_user SET totp_enabled_at = now(), totp_last_step = :step WHERE id = :id AND totp_enabled_at IS NULL")
            .param("id", id)
            .param("step", step)
            .update() == 1

    /** Accepts a TOTP step only if it is newer than the last one used (no replay, also under concurrency). */
    fun advanceTotpStep(
        id: UUID,
        step: Long,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE app_user SET totp_last_step = :step
                 WHERE id = :id AND (totp_last_step IS NULL OR totp_last_step < :step)
                """.trimIndent(),
            ).param("id", id)
            .param("step", step)
            .update() == 1

    fun replaceRecoveryCodes(
        userId: UUID,
        hashes: List<ByteArray>,
    ) {
        jdbc.sql("DELETE FROM recovery_code WHERE user_id = :userId").param("userId", userId).update()
        hashes.forEach { hash ->
            jdbc
                .sql("INSERT INTO recovery_code (id, user_id, code_hash) VALUES (:id, :userId, :hash)")
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("hash", hash)
                .update()
        }
    }

    /** Marks an unused recovery code as used. Returns false if there is none. */
    fun useRecoveryCode(
        userId: UUID,
        hash: ByteArray,
    ): Boolean =
        jdbc
            .sql("UPDATE recovery_code SET used_at = now() WHERE user_id = :userId AND code_hash = :hash AND used_at IS NULL")
            .param("userId", userId)
            .param("hash", hash)
            .update() >= 1

    fun insertToken(
        hash: ByteArray,
        userId: UUID,
        purpose: String,
        validFor: Duration,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO user_token (token_hash, user_id, purpose, expires_at)
                VALUES (:hash, :userId, :purpose, now() + make_interval(secs => :seconds))
                """.trimIndent(),
            ).param("hash", hash)
            .param("userId", userId)
            .param("purpose", purpose)
            .param("seconds", validFor.toSeconds())
            .update()
    }

    /** Uses a valid, unexpired token once. Returns its user, or null. */
    fun consumeToken(
        hash: ByteArray,
        purpose: String,
    ): UUID? =
        jdbc
            .sql(
                """
                UPDATE user_token SET used_at = now()
                 WHERE token_hash = :hash AND purpose = :purpose AND used_at IS NULL AND expires_at > now()
                RETURNING user_id
                """.trimIndent(),
            ).param("hash", hash)
            .param("purpose", purpose)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    fun hasKnownDevices(userId: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM known_device WHERE user_id = :userId)")
            .param("userId", userId)
            .query(Boolean::class.java)
            .single()

    /** Records a sign-in from a device. Returns true if the device was new. */
    fun touchDevice(
        userId: UUID,
        deviceHash: ByteArray,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO known_device (user_id, device_hash) VALUES (:userId, :hash)
                ON CONFLICT (user_id, device_hash) DO UPDATE SET last_seen = now()
                RETURNING (xmax = 0) AS inserted
                """.trimIndent(),
            ).param("userId", userId)
            .param("hash", deviceHash)
            .query(Boolean::class.java)
            .single()

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = UserRow(
        id = rs.getObject("id", UUID::class.java),
        email = rs.getString("email"),
        displayName = rs.getString("display_name"),
        passwordHash = rs.getString("password_hash"),
        locale = rs.getString("locale"),
        emailVerifiedAt = rs.instant("email_verified_at"),
        totpSecret = rs.getString("totp_secret"),
        totpEnabledAt = rs.instant("totp_enabled_at"),
        totpLastStep = rs.getLong("totp_last_step").takeUnless { rs.wasNull() },
        lockedUntil = rs.instant("locked_until"),
    )

    private companion object {
        const val SELECT =
            "SELECT id, email, display_name, password_hash, locale, email_verified_at, totp_secret, " +
                "totp_enabled_at, totp_last_step, locked_until FROM app_user"
    }
}

internal data class WorkspaceRow(
    val id: UUID,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val countryCode: String,
    val vatId: String?,
    val taxNumber: String?,
    val plan: String,
    val version: Int,
    val updatedAt: OffsetDateTime,
)

internal data class WorkspaceChanges(
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val countryCode: String,
    val vatId: String?,
    val taxNumber: String?,
)

/** Tenant table: every query runs under row-level security for the current workspace. */
@Repository
internal class WorkspaceRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        id: UUID,
        name: String,
    ) {
        jdbc
            .sql("INSERT INTO workspace (id, name) VALUES (:id, :name)")
            .param("id", id)
            .param("name", name.trim())
            .update()
    }

    fun find(id: UUID): WorkspaceRow? =
        jdbc
            .sql("$SELECT WHERE id = :id")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    /** Optimistic update: only if [expectedVersion] is still current. Returns the new row, or null. */
    fun update(
        id: UUID,
        expectedVersion: Int,
        changes: WorkspaceChanges,
    ): WorkspaceRow? =
        jdbc
            .sql(
                """
                UPDATE workspace
                   SET name = :name, street = :street, postal_code = :postalCode, city = :city,
                       country_code = :countryCode, vat_id = :vatId, tax_number = :taxNumber,
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :version
                RETURNING $COLUMNS
                """.trimIndent(),
            ).param("id", id)
            .param("version", expectedVersion)
            .param("name", changes.name)
            .param("street", changes.street)
            .param("postalCode", changes.postalCode)
            .param("city", changes.city)
            .param("countryCode", changes.countryCode)
            .param("vatId", changes.vatId)
            .param("taxNumber", changes.taxNumber)
            .query(::map)
            .optional()
            .orElse(null)

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = WorkspaceRow(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        street = rs.getString("street"),
        postalCode = rs.getString("postal_code"),
        city = rs.getString("city"),
        countryCode = rs.getString("country_code"),
        vatId = rs.getString("vat_id"),
        taxNumber = rs.getString("tax_number"),
        plan = rs.getString("plan"),
        version = rs.getInt("version"),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
    )

    private companion object {
        const val COLUMNS = "id, name, street, postal_code, city, country_code, vat_id, tax_number, plan, version, updated_at"
        const val SELECT = "SELECT $COLUMNS FROM workspace"
    }
}

internal data class MembershipRow(
    val id: UUID,
    val workspaceId: UUID,
    val userId: UUID,
    val role: WorkspaceRole,
    val displayName: String,
    val email: String,
    val totpEnabled: Boolean,
)

@Repository
internal class MembershipRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        workspaceId: UUID,
        userId: UUID,
        role: WorkspaceRole,
    ) {
        jdbc
            .sql("INSERT INTO membership (id, workspace_id, user_id, role) VALUES (:id, :ws, :user, :role)")
            .param("id", UUID.randomUUID())
            .param("ws", workspaceId)
            .param("user", userId)
            .param("role", role.name)
            .update()
    }

    /** All memberships of a user, oldest first. Needs `app.user_id` (TenantContext.enterUser). */
    fun forUser(userId: UUID): List<MembershipRow> =
        jdbc
            .sql("$SELECT WHERE m.user_id = :user ORDER BY m.created_at, m.id")
            .param("user", userId)
            .query(::map)
            .list()

    /** Members of the current workspace (explicit filter on top of RLS). */
    fun inCurrentWorkspace(): List<MembershipRow> =
        jdbc
            .sql("$SELECT WHERE m.workspace_id = current_workspace_id() ORDER BY u.display_name, m.id")
            .query(::map)
            .list()

    fun findInCurrentWorkspace(id: UUID): MembershipRow? =
        jdbc
            .sql("$SELECT WHERE m.id = :id AND m.workspace_id = current_workspace_id()")
            .param("id", id)
            .query(::map)
            .optional()
            .orElse(null)

    private fun map(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ) = MembershipRow(
        id = rs.getObject("id", UUID::class.java),
        workspaceId = rs.getObject("workspace_id", UUID::class.java),
        userId = rs.getObject("user_id", UUID::class.java),
        role = WorkspaceRole.valueOf(rs.getString("role")),
        displayName = rs.getString("display_name"),
        email = rs.getString("email"),
        totpEnabled = rs.getObject("totp_enabled_at") != null,
    )

    private companion object {
        const val SELECT =
            "SELECT m.id, m.workspace_id, m.user_id, m.role, u.display_name, u.email, u.totp_enabled_at " +
                "FROM membership m JOIN app_user u ON u.id = m.user_id"
    }
}

private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
