package de.erika.identity

import de.erika.platform.audit.AuditableEvent
import de.erika.platform.mail.Outbox
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.tenancy.TenantContext
import de.erika.platform.web.ErikaException
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

@ConfigurationProperties("erika.passwords")
data class PasswordProperties(
    /** Check new passwords against known breaches (ASVS 2.1.7) with the k-anonymity range API. */
    val breachCheck: Boolean = true,
    val breachApi: String = "https://api.pwnedpasswords.com",
    val resetTokenValidity: Duration = Duration.ofHours(1),
)

/**
 * Password rules for new passwords (sign-up, reset, change): at least 12 characters (bean
 * validation), not containing the email address or workspace name, and not in a known breach.
 *
 * The breach check sends only the first 5 hex characters of the password's SHA-1 (k-anonymity)
 * and asks for padded answers. If the service can't be reached, the password is accepted (fail
 * open): availability of sign-up matters more, and the other rules still apply.
 */
@Component
class PasswordPolicy(
    private val props: PasswordProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: RestClient =
        RestClient
            .builder()
            .baseUrl(props.breachApi)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(2))
                    setReadTimeout(Duration.ofSeconds(3))
                },
            ).build()

    fun check(
        password: String,
        email: String,
        workspaceName: String? = null,
    ) {
        val lowered = password.lowercase()
        if (lowered.contains(email.trim().lowercase()) || workspaceName?.let { lowered.contains(it.trim().lowercase()) } == true) {
            throw IdentityErrors.weakPassword()
        }
        if (password.length < MIN_LENGTH) throw IdentityErrors.weakPassword()
        if (props.breachCheck && breached(password)) {
            throw ErikaException(
                "PASSWORD_BREACHED",
                HttpStatus.BAD_REQUEST,
                "This password appears in known data breaches. Please choose another one.",
            )
        }
    }

    /** True if the password appears in the breach corpus; false if not or if the service is unavailable. */
    fun breached(password: String): Boolean {
        val sha1 = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(password.toByteArray()))
        val prefix = sha1.take(PREFIX)
        val suffix = sha1.drop(PREFIX)
        return try {
            val body =
                http
                    .get()
                    .uri("/range/{prefix}", prefix)
                    .header("Add-Padding", "true")
                    .retrieve()
                    .body(String::class.java)
                    .orEmpty()
            body.lineSequence().any { line ->
                val (hash, count) = line.trim().split(':').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
                hash.equals(suffix, ignoreCase = true) && count > 0
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("Breached-password check unavailable ({}); password accepted", e.javaClass.simpleName)
            false
        }
    }

    private companion object {
        const val PREFIX = 5
        const val MIN_LENGTH = 12
    }
}

data class PasswordResetRequested(
    override val workspaceId: UUID,
    override val actorId: UUID,
) : AuditableEvent {
    override val action = "identity.password.reset_requested"
}

data class PasswordChanged(
    override val workspaceId: UUID,
    override val actorId: UUID,
    val method: String,
) : AuditableEvent {
    override val action = "identity.password.changed"
    override val details get() = mapOf("method" to method)
}

/**
 * Password reset by email and password change (ASVS 2.1.6, 2.5, ADR 0034).
 * - A reset link is single use, valid for an hour, and only its SHA-256 is stored. Asking for
 *   it answers the same whether or not the address exists.
 * - A reset doesn't touch the second factor: signing in still needs TOTP or a recovery code.
 * - After a reset every session of the user ends; after a change every other session ends.
 * - Both are audited in each of the user's workspaces and confirmed by email.
 */
@Service
@Profile(RuntimeInstance.PROFILE_API)
internal class PasswordService(
    private val users: UserRepository,
    private val memberships: MembershipRepository,
    private val tenant: TenantContext,
    private val hasher: PasswordHasher,
    private val policy: PasswordPolicy,
    private val mails: IdentityMails,
    private val outbox: Outbox,
    private val events: ApplicationEventPublisher,
    private val jdbc: JdbcClient,
    private val props: PasswordProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    fun requestReset(email: String) {
        tx.executeWithoutResult {
            val user = users.findByEmail(email) ?: return@executeWithoutResult
            if (user.emailVerifiedAt == null) return@executeWithoutResult
            val token = Tokens.newToken()
            users.insertToken(Tokens.sha256(token), user.id, PASSWORD_RESET, props.resetTokenValidity)
            outbox.send(mails.passwordReset(user, token))
            eachWorkspace(user.id) { events.publishEvent(PasswordResetRequested(it, user.id)) }
        }
    }

    fun confirmReset(
        token: String,
        newPassword: String,
    ) {
        tx.executeWithoutResult {
            val userId = users.consumeToken(Tokens.sha256(token.trim()), PASSWORD_RESET) ?: throw IdentityErrors.invalidToken()
            val user = checkNotNull(users.findById(userId))
            policy.check(newPassword, user.email)
            users.updatePassword(user.id, hasher.hash(newPassword))
            users.resetFailures(user.id)
            endSessions(user.id, keep = null)
            outbox.send(mails.passwordChanged(user))
            eachWorkspace(user.id) { events.publishEvent(PasswordChanged(it, user.id, "reset")) }
        }
    }

    /** Runs in the request's tenant transaction; [currentSessionId] stays signed in. */
    fun change(
        userId: UUID,
        workspaceId: UUID,
        currentPassword: String,
        newPassword: String,
        currentSessionId: String?,
    ) {
        val user = users.findById(userId) ?: throw IdentityErrors.loginRequired()
        if (!hasher.matches(currentPassword, user.passwordHash)) {
            throw ErikaException("CURRENT_PASSWORD_WRONG", HttpStatus.BAD_REQUEST, "The current password is wrong.")
        }
        policy.check(newPassword, user.email)
        users.updatePassword(user.id, hasher.hash(newPassword))
        endSessions(user.id, keep = currentSessionId)
        outbox.send(mails.passwordChanged(user))
        // Audited in the current workspace only: the request's RLS scope stays as it is.
        events.publishEvent(PasswordChanged(workspaceId, user.id, "change"))
    }

    private fun endSessions(
        userId: UUID,
        keep: String?,
    ) {
        jdbc
            .sql("DELETE FROM spring_session WHERE principal_name = :user AND (CAST(:keep AS text) IS NULL OR session_id <> :keep)")
            .param("user", userId.toString())
            .param("keep", keep)
            .update()
    }

    private fun eachWorkspace(
        userId: UUID,
        block: (UUID) -> Unit,
    ) {
        tenant.enterUser(userId)
        memberships.forUser(userId).map { it.workspaceId }.forEach(block)
    }

    private companion object {
        const val PASSWORD_RESET = "PASSWORD_RESET"
    }
}
