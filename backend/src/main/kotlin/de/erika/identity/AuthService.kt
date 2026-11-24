package de.erika.identity

import de.erika.platform.mail.Outbox
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.WorkspaceRole
import de.erika.platform.tenancy.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

internal data class SignUp(
    val email: String,
    val password: String,
    val displayName: String,
    val workspaceName: String?,
    val locale: String,
    val termsAccepted: Boolean,
    val invitationCode: String? = null,
    /** Token of a workspace invitation: join that workspace instead of creating one. */
    val workspaceInvitation: String? = null,
)

/** Result of the password step: who is signing in, into which workspace, and what comes next. */
internal data class PasswordAccepted(
    val userId: UUID,
    val workspaceId: UUID,
    val role: WorkspaceRole,
    val totpEnabled: Boolean,
)

internal data class LoginCompleted(
    /** Set when the browser had no (valid) device cookie: the new cookie value. */
    val newDeviceToken: String?,
)

/**
 * Sign-up, email verification and the two-step sign-in (password, then TOTP or a
 * recovery code). HTTP session handling is in [SessionManager]; this class only knows
 * users, workspaces and the database.
 *
 * Failed attempts (password or code) count towards the lockout and are committed in
 * their own transaction, so they stick although the request fails.
 */
@Service
@Profile(RuntimeInstance.PROFILE_API)
internal class AuthService(
    private val users: UserRepository,
    private val workspaces: WorkspaceRepository,
    private val memberships: MembershipRepository,
    private val tenant: TenantContext,
    private val passwords: PasswordHasher,
    private val totp: Totp,
    private val cipher: TotpSecretCipher,
    private val mails: IdentityMails,
    private val outbox: Outbox,
    private val events: ApplicationEventPublisher,
    private val props: IdentityProperties,
    private val signupPolicy: SignupPolicy,
    private val invitations: Invitations,
    private val passwordPolicy: PasswordPolicy,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val newTx =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /**
     * Creates user, workspace and OWNER membership and sends the verification email.
     * An address that is already registered gets a notice instead (no account enumeration).
     * Needs accepted terms and, during the closed beta, an invitation for this address.
     */
    fun signUp(cmd: SignUp) {
        val invitation = cmd.workspaceInvitation?.takeIf { it.isNotBlank() }?.let(invitations::preview)
        val workspaceName: String
        if (invitation != null) {
            // The invitation was sent to this address, so it also counts as the beta invitation.
            signupPolicy.checkTerms(cmd.termsAccepted)
            if (!invitation.email.equals(cmd.email.trim(), ignoreCase = true)) throw IdentityErrors.invitationForOtherAddress()
            workspaceName = invitation.workspaceName
        } else {
            signupPolicy.check(cmd.email, cmd.termsAccepted, cmd.invitationCode)
            workspaceName = cmd.workspaceName?.trim()?.takeIf { it.isNotEmpty() } ?: throw IdentityErrors.workspaceNameRequired()
        }
        passwordPolicy.check(cmd.password, cmd.email, workspaceName)
        val hash = passwords.hash(cmd.password)
        tx.executeWithoutResult {
            val existing = users.findByEmail(cmd.email)
            if (existing != null) {
                outbox.send(mails.alreadyRegistered(existing))
                return@executeWithoutResult
            }
            val userId = UUID.randomUUID()
            users.insert(userId, cmd.email, cmd.displayName, hash, cmd.locale, signupPolicy.termsVersion)
            if (invitation != null) {
                // Joins the inviting workspace with the invited role (scopes the transaction to it).
                val joined = invitations.accept(checkNotNull(cmd.workspaceInvitation), userId, cmd.email)
                events.publishEvent(TermsAccepted(joined.workspaceId, userId, signupPolicy.termsVersion))
            } else {
                val workspaceId = UUID.randomUUID()
                tenant.enterWorkspace(workspaceId)
                workspaces.insert(workspaceId, workspaceName)
                memberships.insert(workspaceId, userId, WorkspaceRole.OWNER)
                events.publishEvent(WorkspaceCreated(workspaceId, userId))
                events.publishEvent(TermsAccepted(workspaceId, userId, signupPolicy.termsVersion))
            }

            val token = Tokens.newToken()
            users.insertToken(Tokens.sha256(token), userId, VERIFY_EMAIL, props.verificationTokenValidity)
            outbox.send(mails.verifyEmail(users.findById(userId)!!, token))
        }
    }

    fun verifyEmail(token: String) {
        tx.executeWithoutResult {
            val userId = users.consumeToken(Tokens.sha256(token), VERIFY_EMAIL) ?: throw IdentityErrors.invalidToken()
            users.markEmailVerified(userId)
            tenant.enterUser(userId)
            memberships.forUser(userId).forEach { events.publishEvent(EmailVerified(it.workspaceId, userId)) }
        }
    }

    /** Password step. Unknown user, wrong password and locked account look the same to the caller. */
    fun checkPassword(
        email: String,
        password: String,
    ): PasswordAccepted {
        val user = users.findByEmail(email)
        if (user == null || user.isLocked(Instant.now())) {
            passwords.matches(password, null) // same work as a real check
            throw IdentityErrors.invalidCredentials()
        }
        if (!passwords.matches(password, user.passwordHash)) {
            recordFailure(user, "password")
            throw IdentityErrors.invalidCredentials()
        }
        if (user.emailVerifiedAt == null) throw IdentityErrors.emailNotVerified()

        val membership =
            tx.execute {
                tenant.enterUser(user.id)
                memberships.forUser(user.id).firstOrNull()
            } ?: throw IdentityErrors.invalidCredentials()
        return PasswordAccepted(user.id, membership.workspaceId, membership.role, user.totpEnabled)
    }

    /** Second step with a code from the authenticator app or a recovery code. */
    fun verifySecondFactor(
        pending: PendingLogin,
        code: String?,
        recoveryCode: String?,
        deviceToken: String?,
        deviceLabel: String,
    ): LoginCompleted {
        val user = users.findById(pending.userId) ?: throw IdentityErrors.loginRequired()
        if (!user.totpEnabled || user.totpSecret == null) throw IdentityErrors.wrongStep()
        if (user.isLocked(Instant.now())) throw IdentityErrors.invalidCode()

        val method =
            when {
                code != null && recoveryCode == null -> {
                    val step = totp.verify(cipher.decrypt(user.totpSecret), code, user.totpLastStep)
                    if (step != null && users.advanceTotpStep(user.id, step)) "totp" else null
                }

                recoveryCode != null && code == null -> {
                    val hash = Tokens.sha256(Tokens.normaliseRecoveryCode(recoveryCode))
                    if (users.useRecoveryCode(user.id, hash)) "recovery_code" else null
                }

                else -> {
                    throw IdentityErrors.invalidCode()
                }
            }
        if (method == null) {
            recordFailure(user, "second_factor")
            throw IdentityErrors.invalidCode()
        }
        return complete(user, pending, method, deviceToken, deviceLabel)
    }

    /** Creates (or replaces) the not-yet-confirmed TOTP secret. */
    fun startEnrolment(pending: PendingLogin): Pair<String, String> {
        val user = users.findById(pending.userId) ?: throw IdentityErrors.loginRequired()
        val secret = Totp.newSecret()
        if (!users.setPendingTotpSecret(user.id, cipher.encrypt(secret))) throw IdentityErrors.wrongStep()
        return Base32.encode(secret) to Totp.otpauthUri(secret, user.email)
    }

    /** Confirms the first code, turns 2FA on and returns fresh recovery codes (shown once). */
    fun confirmEnrolment(
        pending: PendingLogin,
        code: String,
        deviceToken: String?,
        deviceLabel: String,
    ): Pair<List<String>, LoginCompleted> {
        val user = users.findById(pending.userId) ?: throw IdentityErrors.loginRequired()
        if (user.totpEnabled) throw IdentityErrors.wrongStep()
        val secret = user.totpSecret?.let(cipher::decrypt) ?: throw IdentityErrors.wrongStep()
        val step = totp.verify(secret, code, null)
        if (step == null) {
            recordFailure(user, "totp_enrolment")
            throw IdentityErrors.invalidCode()
        }
        val codes = List(props.recoveryCodeCount) { Tokens.newRecoveryCode() }
        val completed =
            tx.execute {
                if (!users.enableTotp(user.id, step)) throw IdentityErrors.wrongStep()
                users.replaceRecoveryCodes(user.id, codes.map { Tokens.sha256(Tokens.normaliseRecoveryCode(it)) })
                tenant.enterWorkspace(pending.workspaceId)
                events.publishEvent(TotpEnrolled(pending.workspaceId, user.id))
                complete(user, pending, "totp_enrolment", deviceToken, deviceLabel)
            }!!
        return codes to completed
    }

    fun logout(
        userId: UUID,
        workspaceId: UUID,
    ) {
        tx.executeWithoutResult {
            tenant.enterWorkspace(workspaceId)
            events.publishEvent(LoggedOut(workspaceId, userId))
        }
    }

    private fun complete(
        user: UserRow,
        pending: PendingLogin,
        method: String,
        deviceToken: String?,
        deviceLabel: String,
    ): LoginCompleted =
        tx.execute {
            users.resetFailures(user.id)
            val hadDevices = users.hasKnownDevices(user.id)
            val token = deviceToken ?: Tokens.newToken()
            val newDevice = users.touchDevice(user.id, Tokens.sha256(token))
            if (newDevice && hadDevices) outbox.send(mails.newDevice(user, deviceLabel))
            tenant.enterWorkspace(pending.workspaceId)
            events.publishEvent(LoginSucceeded(pending.workspaceId, user.id, method, newDevice))
            LoginCompleted(newDeviceToken = token.takeIf { it != deviceToken })
        }!!

    /** Counts the failure and records it in every workspace of the user, in its own transaction. */
    private fun recordFailure(
        user: UserRow,
        step: String,
    ) {
        newTx.executeWithoutResult {
            val locked = users.recordFailure(user.id, props.maxFailedLogins, props.lockDuration)
            tenant.enterUser(user.id)
            val workspaceIds = memberships.forUser(user.id).map { it.workspaceId }
            workspaceIds.forEach { events.publishEvent(LoginFailed(it, user.id, step)) }
            if (locked) {
                val minutes = props.lockDuration.toMinutes()
                workspaceIds.forEach { events.publishEvent(AccountLocked(it, user.id, minutes)) }
                outbox.send(mails.accountLocked(user, minutes))
                log.warn("Account {} locked for {} minutes after repeated failed sign-ins", user.id, minutes)
            }
        }
    }

    private companion object {
        const val VERIFY_EMAIL = "VERIFY_EMAIL"
    }
}
