package de.erika.identity.web

import de.erika.contract.api.AuthApi
import de.erika.contract.model.LoginRequest
import de.erika.contract.model.RecoveryCodes
import de.erika.contract.model.Session
import de.erika.contract.model.SessionState
import de.erika.contract.model.SessionUser
import de.erika.contract.model.SessionWorkspace
import de.erika.contract.model.SignupRequest
import de.erika.contract.model.TotpConfirmRequest
import de.erika.contract.model.TotpEnrolment
import de.erika.contract.model.TotpRequest
import de.erika.contract.model.VerifyEmailRequest
import de.erika.identity.AuthService
import de.erika.identity.IdentityErrors
import de.erika.identity.IdentityProperties
import de.erika.identity.LoginCompleted
import de.erika.identity.PendingLogin
import de.erika.identity.SignUp
import de.erika.identity.UserRepository
import de.erika.identity.WorkspaceRepository
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import de.erika.platform.security.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import de.erika.contract.model.Locale as ApiLocale
import de.erika.contract.model.Role as ApiRole

@RestController
@Profile(RuntimeInstance.PROFILE_API)
internal class AuthController(
    private val auth: AuthService,
    private val sessions: SessionManager,
    private val users: UserRepository,
    private val workspaces: WorkspaceRepository,
    private val request: HttpServletRequest,
    props: IdentityProperties,
) : AuthApi {
    private val attempts = RateLimiter(props.loginRateLimit, props.loginRateWindow)

    override fun getSession(): ResponseEntity<Session> {
        // Touch the deferred CSRF token so the XSRF-TOKEN cookie is written with this response.
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        return ResponseEntity.ok(currentSession())
    }

    override fun signUp(signupRequest: SignupRequest): ResponseEntity<Unit> {
        limit()
        try {
            auth.signUp(
                SignUp(
                    email = signupRequest.email,
                    password = signupRequest.password,
                    displayName = signupRequest.displayName,
                    workspaceName = signupRequest.workspaceName,
                    locale = (signupRequest.locale ?: ApiLocale.DE).value,
                ),
            )
        } catch (e: DuplicateKeyException) {
            // Concurrent sign-up with the same address: same answer as for an existing account.
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }

    override fun verifyEmail(verifyEmailRequest: VerifyEmailRequest): ResponseEntity<Unit> {
        limit()
        auth.verifyEmail(verifyEmailRequest.token)
        return ResponseEntity.noContent().build()
    }

    override fun logIn(loginRequest: LoginRequest): ResponseEntity<Session> {
        limit()
        val accepted = auth.checkPassword(loginRequest.email, loginRequest.password)
        sessions.beginPending(
            PendingLogin(accepted.userId, accepted.workspaceId, accepted.role, !accepted.totpEnabled, Instant.now()),
        )
        return ResponseEntity.ok(currentSession())
    }

    override fun verifyTotp(totpRequest: TotpRequest): ResponseEntity<Session> {
        limit()
        val pending = sessions.pending() ?: throw IdentityErrors.loginRequired()
        if (pending.needsEnrolment) throw IdentityErrors.wrongStep()
        val completed =
            auth.verifySecondFactor(
                pending,
                totpRequest.code,
                totpRequest.recoveryCode,
                sessions.deviceToken(),
                sessions.deviceLabel(),
            )
        signIn(pending, completed)
        return ResponseEntity.ok(currentSession())
    }

    override fun startTotpEnrolment(): ResponseEntity<TotpEnrolment> {
        val pending = sessions.pending() ?: throw IdentityErrors.loginRequired()
        if (!pending.needsEnrolment) throw IdentityErrors.wrongStep()
        val (secret, uri) = auth.startEnrolment(pending)
        return ResponseEntity.ok(TotpEnrolment(secret = secret, otpauthUri = uri))
    }

    override fun confirmTotpEnrolment(totpConfirmRequest: TotpConfirmRequest): ResponseEntity<RecoveryCodes> {
        limit()
        val pending = sessions.pending() ?: throw IdentityErrors.loginRequired()
        if (!pending.needsEnrolment) throw IdentityErrors.wrongStep()
        val (codes, completed) = auth.confirmEnrolment(pending, totpConfirmRequest.code, sessions.deviceToken(), sessions.deviceLabel())
        signIn(pending, completed)
        return ResponseEntity.ok(RecoveryCodes(codes = codes))
    }

    override fun logOut(): ResponseEntity<Unit> {
        ErikaPrincipal.currentOrNull()?.let { auth.logout(it.userId, it.workspaceId) }
        sessions.signOut()
        return ResponseEntity.noContent().build()
    }

    private fun signIn(
        pending: PendingLogin,
        completed: LoginCompleted,
    ) {
        sessions.signIn(ErikaPrincipal(pending.userId, pending.workspaceId, pending.role))
        completed.newDeviceToken?.let(sessions::setDeviceToken)
    }

    private fun limit() {
        if (!attempts.tryAcquire(sessions.clientKey())) throw IdentityErrors.rateLimited()
    }

    private fun currentSession(): Session {
        ErikaPrincipal.currentOrNull()?.let { principal ->
            val user = users.findById(principal.userId) ?: return Session(state = SessionState.ANONYMOUS)
            // Runs inside the request's tenant transaction, so RLS allows exactly this workspace.
            val workspace = workspaces.find(principal.workspaceId)
            return Session(
                state = SessionState.AUTHENTICATED,
                user = SessionUser(user.id, user.email, user.displayName, ApiLocale.forValue(user.locale)),
                workspace = workspace?.let { SessionWorkspace(it.id, it.name) },
                role = ApiRole.valueOf(principal.role.name),
            )
        }
        val pending = sessions.pending() ?: return Session(state = SessionState.ANONYMOUS)
        val user = users.findById(pending.userId) ?: return Session(state = SessionState.ANONYMOUS)
        return Session(
            state = if (pending.needsEnrolment) SessionState.TOTP_ENROLMENT_REQUIRED else SessionState.TOTP_REQUIRED,
            user = SessionUser(user.id, user.email, user.displayName, ApiLocale.forValue(user.locale)),
        )
    }
}
