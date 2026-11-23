package de.erika.identity.web

import de.erika.identity.MembershipRepository
import de.erika.identity.WorkspaceAccess
import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Checks every signed-in API request against the current membership, inside the request's
 * tenant transaction:
 * - the session's role must still be the member's role: a removed member or one whose role
 *   changed is signed out (401 `SESSION_REVOKED`; on `/auth/` endpoints the request continues
 *   anonymously, so `GET /auth/session` simply answers ANONYMOUS);
 * - a closed workspace is read-only (EX-04): state-changing requests answer 403
 *   `WORKSPACE_CLOSED`, except exports, feedback and the sign-in endpoints.
 */
@Component
@Profile(RuntimeInstance.PROFILE_API)
internal class WorkspaceAccessInterceptor(
    private val memberships: MembershipRepository,
    private val sessions: SessionManager,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val principal = ErikaPrincipal.currentOrNull() ?: return true
        val path = request.requestURI
        val access = memberships.access(principal.userId)
        if (access == null || access.first != principal.role) {
            sessions.signOut()
            if (path.startsWith(AUTH)) return true
            throw WorkspaceAccess.revoked()
        }
        val closed = access.second
        if (closed && request.method !in READ_METHODS && ALLOWED_WHEN_CLOSED.none { path == it || path.startsWith("$it/") }) {
            throw WorkspaceAccess.closed()
        }
        return true
    }

    private companion object {
        const val AUTH = "/api/v1/auth/"
        val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")

        /** Reading data out and leaving: exports, feedback, sign-in/out and switching workspace. */
        val ALLOWED_WHEN_CLOSED = listOf("/api/v1/exports", "/api/v1/feedback", "/api/v1/auth")
    }
}

@Configuration(proxyBeanMethods = false)
@Profile(RuntimeInstance.PROFILE_API)
internal class WorkspaceAccessConfiguration(
    private val interceptor: WorkspaceAccessInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**")
    }
}
