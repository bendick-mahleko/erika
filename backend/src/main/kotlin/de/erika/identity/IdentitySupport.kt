package de.erika.identity

import de.erika.platform.mail.Mail
import de.erika.platform.mail.MailProperties
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.web.ErikaException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.Locale

@ConfigurationProperties("erika.identity")
data class IdentityProperties(
    /** Base64 AES-256 key for TOTP secrets at rest. Required for the API role. */
    val totpEncryptionKey: String = "",
    val maxFailedLogins: Int = 5,
    val lockDuration: Duration = Duration.ofMinutes(15),
    /** Sign-in attempts (password or code) per client IP and window. */
    val loginRateLimit: Int = 20,
    val loginRateWindow: Duration = Duration.ofMinutes(5),
    val verificationTokenValidity: Duration = Duration.ofHours(48),
    /** How long the password step stays valid while waiting for the second factor. */
    val pendingLoginTimeout: Duration = Duration.ofMinutes(10),
    val recoveryCodeCount: Int = 10,
    /** `Secure` flag for the device cookie; off only for local http development. */
    val secureCookies: Boolean = true,
)

@Configuration(proxyBeanMethods = false)
@Profile(RuntimeInstance.PROFILE_API)
class IdentityConfiguration {
    @Bean
    fun totpSecretCipher(props: IdentityProperties): TotpSecretCipher {
        check(props.totpEncryptionKey.isNotBlank()) { "Set erika.identity.totp-encryption-key (ERIKA_TOTP_ENCRYPTION_KEY)" }
        return TotpSecretCipher(props.totpEncryptionKey)
    }

    @Bean
    fun totp(): Totp = Totp(Clock.systemUTC())

    @Bean
    fun signupPolicy(props: SignupProperties): SignupPolicy {
        val invitations = props.inviteSecret.takeIf { it.isNotBlank() }?.let { BetaInvitations(it) }
        check(props.mode == SignupMode.OPEN || invitations != null) {
            "erika.signup.mode=INVITE_ONLY needs erika.signup.invite-secret (ERIKA_INVITE_SECRET)"
        }
        return SignupPolicy(props, invitations)
    }
}

/** Stable error codes of the identity module (RFC 9457 `code`). */
internal object IdentityErrors {
    fun invalidCredentials() = ErikaException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, "Email address or password is wrong.")

    fun invalidCode() = ErikaException("INVALID_CODE", HttpStatus.UNAUTHORIZED, "The code is wrong or has expired.")

    fun emailNotVerified() =
        ErikaException("EMAIL_NOT_VERIFIED", HttpStatus.FORBIDDEN, "Please confirm your email address first (see the email we sent you).")

    fun loginRequired() = ErikaException("LOGIN_REQUIRED", HttpStatus.UNAUTHORIZED, "Please sign in with your password first.")

    fun wrongStep() = ErikaException("WRONG_LOGIN_STEP", HttpStatus.CONFLICT, "This step is not possible in the current sign-in state.")

    fun rateLimited() = ErikaException("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please wait a few minutes.")

    fun invalidToken() = ErikaException("INVALID_TOKEN", HttpStatus.BAD_REQUEST, "The link is invalid or has expired.")

    fun weakPassword() =
        ErikaException("WEAK_PASSWORD", HttpStatus.BAD_REQUEST, "The password must not contain your email address or workspace name.")

    fun invitationForOtherAddress() =
        ErikaException(
            "INVITATION_OTHER_ACCOUNT",
            HttpStatus.FORBIDDEN,
            "This invitation is for another email address. Sign in or sign up with the invited address.",
        )

    fun workspaceNameRequired() =
        ErikaException("WORKSPACE_NAME_REQUIRED", HttpStatus.BAD_REQUEST, "Please enter the name of your business.")

    fun notFound(what: String) = ErikaException("${what}_NOT_FOUND", HttpStatus.NOT_FOUND, "Not found.")

    fun preconditionRequired() =
        ErikaException("PRECONDITION_REQUIRED", HttpStatus.PRECONDITION_REQUIRED, "Send If-Match with the ETag you received.")

    fun versionConflict() =
        ErikaException(
            "VERSION_CONFLICT",
            HttpStatus.PRECONDITION_FAILED,
            "Someone else changed this in the meantime. Reload and try again.",
        )
}

/** Account emails, German first (NF-13); texts in `i18n/mail_*.properties`. */
@Component
internal class IdentityMails(
    private val messages: MessageSource,
    private val props: MailProperties,
) {
    fun verifyEmail(
        user: UserRow,
        token: String,
    ) = mail(user, "verify", user.displayName, "${props.webBaseUrl}/verify-email?token=$token")

    fun alreadyRegistered(user: UserRow) = mail(user, "exists", user.displayName, "${props.webBaseUrl}/login")

    fun passwordReset(
        user: UserRow,
        token: String,
    ) = mail(user, "reset", user.displayName, "${props.webBaseUrl}/reset-password?token=$token")

    fun passwordChanged(user: UserRow) = mail(user, "pwchanged", user.displayName, "${props.webBaseUrl}/login/forgot")

    fun accountLocked(
        user: UserRow,
        minutes: Long,
    ) = mail(user, "locked", user.displayName, minutes)

    fun newDevice(
        user: UserRow,
        device: String,
    ) = mail(user, "newdevice", user.displayName, device)

    /** Invitation to a workspace; in the inviter's language (the invitee has no account yet). */
    fun invitation(
        to: String,
        inviter: UserRow,
        workspaceName: String,
        role: de.erika.platform.security.WorkspaceRole,
        token: String,
    ): Mail {
        val locale = Locale.forLanguageTag(inviter.locale)
        val roleText = messages.getMessage("mail.role.${role.name}", null, locale)
        val link = "${props.webBaseUrl}/invitations/accept?token=$token"
        return Mail(
            to = to,
            subject = messages.getMessage("mail.invitation.subject", arrayOf(inviter.displayName), locale),
            body = messages.getMessage("mail.invitation.body", arrayOf(inviter.displayName, workspaceName, roleText, link), locale),
        )
    }

    private fun mail(
        user: UserRow,
        key: String,
        vararg args: Any,
    ): Mail {
        val locale = Locale.forLanguageTag(user.locale)
        return Mail(
            to = user.email,
            subject = messages.getMessage("mail.$key.subject", null, locale),
            body = messages.getMessage("mail.$key.body", args, locale),
        )
    }
}
