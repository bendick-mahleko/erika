package de.erika.identity.web

import de.erika.identity.IdentityProperties
import de.erika.identity.PendingLogin
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseCookie
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * The HTTP side of signing in: the pending password step in the session, the
 * Spring Security context after the second factor, session-ID rotation (no session
 * fixation) and the long-lived device cookie used for new-device notifications.
 */
@Component
@Profile(RuntimeInstance.PROFILE_API)
internal class SessionManager(
    private val request: HttpServletRequest,
    private val response: HttpServletResponse,
    private val contextRepository: SecurityContextRepository,
    private val props: IdentityProperties,
) {
    /** Starts a fresh session waiting for the second factor. Any previous sign-in ends. */
    fun beginPending(pending: PendingLogin) {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        request.getSession(true).setAttribute(PENDING, pending)
    }

    /** The pending password step, if any and not expired. */
    fun pending(): PendingLogin? {
        val pending = request.getSession(false)?.getAttribute(PENDING) as? PendingLogin ?: return null
        return pending.takeIf { it.startedAt.plus(props.pendingLoginTimeout).isAfter(Instant.now()) }
    }

    /** Second factor passed: the session now carries the signed-in principal. */
    fun signIn(principal: ErikaPrincipal) {
        val session = request.getSession(true)
        session.removeAttribute(PENDING)
        request.changeSessionId()
        val context = SecurityContextHolder.createEmptyContext().apply { authentication = principal.toAuthentication() }
        SecurityContextHolder.setContext(context)
        contextRepository.saveContext(context, request, response)
    }

    fun signOut() {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
    }

    fun deviceToken(): String? =
        request.cookies
            ?.firstOrNull { it.name == DEVICE_COOKIE }
            ?.value
            ?.removeSurrounding("\"") // RFC 6265 allows a quoted cookie value
            ?.takeIf { DEVICE_TOKEN.matches(it) }

    fun setDeviceToken(token: String) {
        val cookie =
            ResponseCookie
                .from(DEVICE_COOKIE, token)
                .httpOnly(true)
                .secure(props.secureCookies)
                .sameSite("Lax")
                .path("/")
                .maxAge(DEVICE_COOKIE_AGE)
                .build()
        response.addHeader("Set-Cookie", cookie.toString())
    }

    /** Short description of the client for the new-device email (browser user agent, truncated). */
    fun deviceLabel(): String = (request.getHeader("User-Agent") ?: "unbekannt / unknown").take(MAX_LABEL)

    fun clientKey(): String = request.remoteAddr ?: "unknown"

    private companion object {
        const val PENDING = "erika.pendingLogin"
        const val DEVICE_COOKIE = "erika_device"
        val DEVICE_TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
        val DEVICE_COOKIE_AGE: Duration = Duration.ofDays(400)
        const val MAX_LABEL = 160
    }
}
