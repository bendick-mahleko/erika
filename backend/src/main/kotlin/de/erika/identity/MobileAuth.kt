package de.erika.identity

import de.erika.platform.audit.AuditableEvent
import de.erika.platform.mail.Outbox
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.security.RateLimiter
import de.erika.platform.security.WorkspaceRole
import de.erika.platform.tenancy.TenantContext
import de.erika.platform.web.ErikaException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class DeviceRegistration(
    val name: String,
    val platform: DevicePlatform,
    val appVersion: String?,
)

internal data class IssuedTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

internal data class DeviceSignedIn(
    val deviceId: UUID,
    val tokens: IssuedTokens,
    val holder: DeviceHolder,
    /** Set when this sign-in confirmed the 2FA set-up; shown once. */
    val recoveryCodes: List<String>?,
)

/** Result of checking a bearer token (ADR 0037). */
internal sealed interface TokenCheck {
    data class Valid(
        val principal: ErikaPrincipal,
    ) : TokenCheck

    /** Unknown token, or a refresh token sent as bearer. */
    data object Unknown : TokenCheck

    /** A real access token past its 15 minutes: the app renews it. */
    data object Expired : TokenCheck

    /** The device was revoked or its user is no longer a member: the app deletes its data. */
    data object Revoked : TokenCheck
}

/** Device registered (MARA app sign-in, ADR 0037). */
data class MobileDeviceRegistered(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val deviceId: UUID,
    val platform: String,
    val method: String,
) : AuditableEvent {
    override val action = "identity.device.registered"
    override val details get() = mapOf("deviceId" to deviceId.toString(), "platform" to platform, "method" to method)
}

/** Device locked out; actor null = the system (reused refresh token). */
data class MobileDeviceRevoked(
    override val workspaceId: UUID,
    override val actorId: UUID?,
    val deviceId: UUID,
    val reason: String,
) : AuditableEvent {
    override val action = "identity.device.revoked"
    override val details get() = mapOf("deviceId" to deviceId.toString(), "reason" to reason)
}

internal object MobileAuthErrors {
    fun totpRequired() = ErikaException("TOTP_REQUIRED", HttpStatus.UNAUTHORIZED, "Enter the code from your authenticator app.")

    fun totpEnrolmentRequired() = ErikaException("TOTP_ENROLMENT_REQUIRED", HttpStatus.CONFLICT, "Set up two-factor authentication first.")

    fun totpAlreadyEnabled() =
        ErikaException("TOTP_ALREADY_ENABLED", HttpStatus.CONFLICT, "Two-factor authentication is already set up for this account.")

    fun appNotForRole() = ErikaException("APP_NOT_FOR_ROLE", HttpStatus.FORBIDDEN, "This account can't use the MARA app.")

    fun refreshTokenInvalid() = ErikaException("REFRESH_TOKEN_INVALID", HttpStatus.UNAUTHORIZED, "Please sign in again.")

    fun deviceRevoked() = ErikaException("DEVICE_REVOKED", HttpStatus.UNAUTHORIZED, "This device has been signed out.")

    fun deviceNotFound() = ErikaException("DEVICE_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")
}

/**
 * Sign-in of the MARA app (ADR 0037): password and TOTP once per phone, then opaque bearer
 * tokens. Only SHA-256 hashes of tokens are stored. The workspace of a token request always
 * comes from the token.
 */
@Service
@Profile(RuntimeInstance.PROFILE_API)
internal class MobileAuth(
    private val auth: AuthService,
    private val users: UserRepository,
    private val devices: MobileDeviceRepository,
    private val workspaces: WorkspaceRepository,
    private val tenant: TenantContext,
    private val totp: Totp,
    private val cipher: TotpSecretCipher,
    private val mails: IdentityMails,
    private val outbox: Outbox,
    private val events: ApplicationEventPublisher,
    private val props: IdentityProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val clock: Clock = Clock.systemUTC()
    private val signInAttempts = RateLimiter(props.loginRateLimit, props.loginRateWindow)
    private val refreshAttempts = RateLimiter(props.mobileRefreshRateLimit, props.loginRateWindow)

    /** New, not yet confirmed authenticator secret for an account without 2FA. */
    fun startEnrolment(
        email: String,
        password: String,
        clientKey: String,
    ): Pair<String, String> {
        rateLimit(clientKey)
        val user = auth.verifiedUser(email, password)
        appMembership(user.id)
        if (user.totpEnabled) throw MobileAuthErrors.totpAlreadyEnabled()
        val secret = Totp.newSecret()
        if (!users.setPendingTotpSecret(user.id, cipher.encrypt(secret))) throw MobileAuthErrors.totpAlreadyEnabled()
        return Base32.encode(secret) to Totp.otpauthUri(secret, user.email)
    }

    /** Password, then TOTP (or recovery code, or the first code of a pending set-up); registers the phone. */
    fun signIn(
        email: String,
        password: String,
        code: String?,
        recoveryCode: String?,
        device: DeviceRegistration,
        clientKey: String,
    ): DeviceSignedIn {
        rateLimit(clientKey)
        val user = auth.verifiedUser(email, password)
        val membership = appMembership(user.id)
        if (user.totpEnabled) {
            if (code == null && recoveryCode == null) throw MobileAuthErrors.totpRequired()
            val method = auth.checkSecondFactor(user, code, recoveryCode)
            return checkNotNull(tx.execute { register(user, membership, device, method, null) })
        }
        // No 2FA yet: the code confirms the secret from startEnrolment.
        val pending = user.totpSecret ?: throw MobileAuthErrors.totpEnrolmentRequired()
        if (code == null) throw MobileAuthErrors.totpEnrolmentRequired()
        val step = totp.verify(cipher.decrypt(pending), code, null)
        if (step == null) {
            auth.recordFailure(user, "totp_enrolment")
            throw IdentityErrors.invalidCode()
        }
        val codes = List(props.recoveryCodeCount) { Tokens.newRecoveryCode() }
        return checkNotNull(
            tx.execute {
                if (!users.enableTotp(user.id, step)) throw IdentityErrors.wrongStep()
                users.replaceRecoveryCodes(user.id, codes.map { Tokens.sha256(Tokens.normaliseRecoveryCode(it)) })
                tenant.enterWorkspace(membership.workspaceId)
                events.publishEvent(TotpEnrolled(membership.workspaceId, user.id))
                register(user, membership, device, "totp_enrolment", codes)
            },
        )
    }

    /** Rotates the refresh token. A used one presented again revokes the device (theft). */
    fun refresh(
        refreshToken: String,
        appVersion: String?,
    ): IssuedTokens {
        val hash = Tokens.sha256(refreshToken)
        val workspaceId = devices.tokenWorkspace(hash) ?: throw MobileAuthErrors.refreshTokenInvalid()
        val outcome =
            tx.execute {
                tenant.enterWorkspace(workspaceId)
                val token = devices.findToken(hash)
                if (token == null || token.kind != TokenKind.REFRESH) return@execute RefreshOutcome.Invalid
                if (!refreshAttempts.tryAcquire(token.deviceId.toString())) throw IdentityErrors.rateLimited()
                val device = devices.find(token.deviceId) ?: return@execute RefreshOutcome.Invalid
                if (device.revokedAt != null || device.role == null || !device.role.usesMobileApp) {
                    return@execute RefreshOutcome.Revoked
                }
                if (token.usedAt != null || (token.expiresAt.isAfter(now()) && !devices.useRefreshToken(hash))) {
                    revoke(device, null, DeviceRevokedReason.TOKEN_REUSE)
                    return@execute RefreshOutcome.Revoked
                }
                if (!token.expiresAt.isAfter(now())) return@execute RefreshOutcome.Invalid
                devices.deleteExpiredTokens(device.id)
                devices.touch(device.id, appVersion)
                RefreshOutcome.Renewed(issue(workspaceId, device.id))
            }
        // Committed first, so a revocation for token reuse sticks although the request fails.
        return when (outcome) {
            is RefreshOutcome.Renewed -> outcome.tokens
            RefreshOutcome.Revoked -> throw MobileAuthErrors.deviceRevoked()
            else -> throw MobileAuthErrors.refreshTokenInvalid()
        }
    }

    /**
     * Checks a bearer access token in a transaction of its own, before the request's tenant
     * transaction starts. The role is read from the membership on every request, so a role
     * change applies at once and a removed member is locked out.
     */
    fun authenticate(
        accessToken: String,
        appVersion: String?,
    ): TokenCheck {
        val hash = Tokens.sha256(accessToken)
        val workspaceId = devices.tokenWorkspace(hash) ?: return TokenCheck.Unknown
        return checkNotNull(
            tx.execute {
                tenant.enterWorkspace(workspaceId)
                val token = devices.findToken(hash)
                if (token == null || token.kind != TokenKind.ACCESS) return@execute TokenCheck.Unknown
                val device = devices.find(token.deviceId) ?: return@execute TokenCheck.Unknown
                val role = device.role
                if (device.revokedAt != null || role == null || !role.usesMobileApp) return@execute TokenCheck.Revoked
                if (!token.expiresAt.isAfter(now())) return@execute TokenCheck.Expired
                devices.touch(device.id, appVersion)
                TokenCheck.Valid(ErikaPrincipal(device.userId, workspaceId, role, device.id))
            },
        )
    }

    /** Sign-out from the app: the device is revoked (runs in the request's tenant transaction). */
    fun signOut(principal: ErikaPrincipal) {
        val device = devices.find(checkNotNull(principal.deviceId)) ?: return
        revoke(device, principal.userId, DeviceRevokedReason.SIGNED_OUT)
    }

    /** Owner revokes a device in the web app (runs in the request's tenant transaction). */
    fun revokeByOwner(
        deviceId: UUID,
        owner: UUID,
    ): MobileDeviceRow {
        val device = devices.find(deviceId) ?: throw MobileAuthErrors.deviceNotFound()
        revoke(device, owner, DeviceRevokedReason.OWNER)
        return checkNotNull(devices.find(deviceId))
    }

    fun list(): List<MobileDeviceRow> = devices.inCurrentWorkspace()

    private fun revoke(
        device: MobileDeviceRow,
        by: UUID?,
        reason: DeviceRevokedReason,
    ) {
        if (devices.revoke(device.id, by, reason)) {
            events.publishEvent(MobileDeviceRevoked(device.workspaceId, by, device.id, reason.name))
        }
    }

    private fun register(
        user: UserRow,
        membership: MembershipRow,
        device: DeviceRegistration,
        method: String,
        recoveryCodes: List<String>?,
    ): DeviceSignedIn {
        users.resetFailures(user.id)
        tenant.enterWorkspace(membership.workspaceId)
        val deviceId = UUID.randomUUID()
        devices.insert(deviceId, membership.workspaceId, user.id, device.name.trim(), device.platform, device.appVersion)
        val tokens = issue(membership.workspaceId, deviceId)
        events.publishEvent(MobileDeviceRegistered(membership.workspaceId, user.id, deviceId, device.platform.name, method))
        // Like a sign-in from a new browser: the user hears about every new phone.
        outbox.send(mails.newDevice(user, "MARA-App: ${device.name.trim()}"))
        val workspaceName = workspaces.find(membership.workspaceId)?.name.orEmpty()
        return DeviceSignedIn(
            deviceId,
            tokens,
            DeviceHolder(user.id, user.displayName, membership.role, workspaceName),
            recoveryCodes,
        )
    }

    private fun issue(
        workspaceId: UUID,
        deviceId: UUID,
    ): IssuedTokens {
        val access = Tokens.newToken()
        val refresh = Tokens.newToken()
        val accessExpires = now().plus(props.mobileAccessTokenValidity)
        val refreshExpires = now().plus(props.mobileRefreshTokenValidity)
        devices.insertToken(Tokens.sha256(access), workspaceId, deviceId, TokenKind.ACCESS, accessExpires)
        devices.insertToken(Tokens.sha256(refresh), workspaceId, deviceId, TokenKind.REFRESH, refreshExpires)
        return IssuedTokens(access, accessExpires, refresh, refreshExpires)
    }

    /** The oldest membership whose role may use the app; tax advisors don't (ADR 0039). */
    private fun appMembership(userId: UUID): MembershipRow =
        auth.membershipsOf(userId).firstOrNull { it.role.usesMobileApp } ?: throw MobileAuthErrors.appNotForRole()

    private fun rateLimit(clientKey: String) {
        if (!signInAttempts.tryAcquire(clientKey)) throw IdentityErrors.rateLimited()
    }

    private fun now(): Instant = clock.instant()

    private sealed interface RefreshOutcome {
        data class Renewed(
            val tokens: IssuedTokens,
        ) : RefreshOutcome

        data object Revoked : RefreshOutcome

        data object Invalid : RefreshOutcome
    }
}

/** Whether this role may use the MARA app: everyone but tax advisors (ADR 0039). */
internal val WorkspaceRole.usesMobileApp: Boolean get() = this != WorkspaceRole.ADVISOR
