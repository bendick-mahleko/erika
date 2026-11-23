package de.erika.identity

import de.erika.platform.web.ErikaException
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Who may sign up (ADR 0030). */
enum class SignupMode { OPEN, INVITE_ONLY }

@ConfigurationProperties("erika.signup")
data class SignupProperties(
    /** INVITE_ONLY during the closed beta (staging and production); OPEN locally and from go-live. */
    val mode: SignupMode = SignupMode.OPEN,
    /** HMAC key for invitation links (`openssl rand -hex 32`); required when [mode] is INVITE_ONLY. */
    val inviteSecret: String = "",
    /** Version of the terms incl. data-processing agreement that new users accept. */
    val termsVersion: String = "beta-2026-12",
)

/**
 * Invitation links for the closed beta: `v1.<payload>.<signature>`, where the payload is
 * Base64url of `<email>|<expiry epoch seconds>` and the signature is Base64url of
 * HMAC-SHA256 over `v1.<payload>` with the invite secret.
 *
 * A link is bound to one email address. A second sign-up with the same address only
 * sends the "already registered" notice, so a link can't create a second account and
 * needs no table. Links are created by the operator script `infra/production/invite.py`,
 * which implements the same format (cross-checked in `BetaInvitationsTest`).
 */
class BetaInvitations(
    secret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val key = SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256")

    init {
        require(secret.length >= 32) { "The invite secret must have at least 32 characters" }
    }

    fun create(
        email: String,
        expires: Instant,
    ): String {
        val payload = B64.encodeToString("${normalise(email)}|${expires.epochSecond}".toByteArray(UTF_8))
        return "$PREFIX$payload.${sign(payload)}"
    }

    /** True if [code] is a valid, unexpired invitation for [email]. */
    fun isValidFor(
        code: String,
        email: String,
    ): Boolean {
        if (!code.startsWith(PREFIX)) return false
        val parts = code.removePrefix(PREFIX).split('.')
        if (parts.size != 2) return false
        val (payload, signature) = parts
        if (!MessageDigest.isEqual(sign(payload).toByteArray(UTF_8), signature.toByteArray(UTF_8))) return false
        val decoded =
            try {
                String(Base64.getUrlDecoder().decode(payload), UTF_8)
            } catch (e: IllegalArgumentException) {
                return false
            }
        val invitedEmail = decoded.substringBeforeLast('|', "")
        val expiry = decoded.substringAfterLast('|', "").toLongOrNull() ?: return false
        return invitedEmail == normalise(email) && clock.instant().epochSecond < expiry
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return B64.encodeToString(mac.doFinal("$PREFIX$payload".toByteArray(UTF_8)))
    }

    private fun normalise(email: String) = email.trim().lowercase()

    companion object {
        private const val PREFIX = "v1."
        private val B64 = Base64.getUrlEncoder().withoutPadding()
    }
}

/** Sign-up rules: invitation during the closed beta, accepted terms always. */
class SignupPolicy(
    private val props: SignupProperties,
    private val invitations: BetaInvitations?,
) {
    val inviteOnly get() = props.mode == SignupMode.INVITE_ONLY
    val termsVersion get() = props.termsVersion

    fun check(
        email: String,
        termsAccepted: Boolean,
        invitationCode: String?,
    ) {
        checkTerms(termsAccepted)
        if (!inviteOnly) return
        if (invitationCode.isNullOrBlank()) throw invitationRequired()
        if (invitations?.isValidFor(invitationCode, email) != true) throw invitationInvalid()
    }

    /** Terms only: a workspace invitation replaces the closed-beta invitation (ADR 0032). */
    fun checkTerms(termsAccepted: Boolean) {
        if (!termsAccepted) throw termsNotAccepted()
    }

    private companion object {
        fun termsNotAccepted() =
            ErikaException("TERMS_NOT_ACCEPTED", HttpStatus.BAD_REQUEST, "Please accept the terms and the data-processing agreement.")

        fun invitationRequired() =
            ErikaException("INVITATION_REQUIRED", HttpStatus.FORBIDDEN, "ERIKA is in a closed beta; sign-up needs an invitation.")

        fun invitationInvalid() =
            ErikaException(
                "INVITATION_INVALID",
                HttpStatus.FORBIDDEN,
                "The invitation is invalid, has expired or is for another email address.",
            )
    }
}
