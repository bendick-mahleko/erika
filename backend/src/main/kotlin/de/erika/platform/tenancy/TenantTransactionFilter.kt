package de.erika.platform.tenancy

import de.erika.platform.runtime.RuntimeInstance
import de.erika.platform.security.ErikaPrincipal
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Software Design §7: each request of a signed-in user runs in one transaction with
 * `SET LOCAL app.workspace_id = <workspace from the session>`, so row-level security
 * filters every tenant table. The workspace never comes from the URL or the body.
 *
 * Runs after Spring Security (which restores the principal from the session). Responses
 * with status >= 400 roll the transaction back, also when an exception handler turned
 * the exception into a response.
 */
@Component
@Profile(RuntimeInstance.PROFILE_API)
@Order(Ordered.LOWEST_PRECEDENCE - 100)
class TenantTransactionFilter(
    transactionManager: PlatformTransactionManager,
    private val tenant: TenantContext,
) : OncePerRequestFilter() {
    private val tx = TransactionTemplate(transactionManager)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = ErikaPrincipal.currentOrNull()
        if (principal == null) {
            filterChain.doFilter(request, response)
            return
        }
        tx.executeWithoutResult { status ->
            // Only the workspace: app.user_id (which widens RLS to all of a user's memberships)
            // is for the sign-in flow, never for normal requests.
            tenant.enterWorkspace(principal.workspaceId)
            filterChain.doFilter(request, response)
            if (response.status >= 400) status.setRollbackOnly()
        }
    }
}
