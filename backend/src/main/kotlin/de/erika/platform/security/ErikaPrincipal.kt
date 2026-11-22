package de.erika.platform.security

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.AuthenticatedPrincipal
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.io.Serializable
import java.util.UUID

/** Role of a user in one workspace (Requirements §2). */
enum class WorkspaceRole { OWNER, MEMBER, ADVISOR }

/**
 * A fully signed-in user (password + second factor), bound to one workspace.
 * Stored in the server-side session; the workspace never comes from the request.
 */
data class ErikaPrincipal(
    val userId: UUID,
    val workspaceId: UUID,
    val role: WorkspaceRole,
) : AuthenticatedPrincipal,
    Serializable {
    /** Name used by Spring Security and Spring Session (`principal_name`): the user ID. */
    override fun getName(): String = userId.toString()

    fun toAuthentication(): Authentication =
        UsernamePasswordAuthenticationToken.authenticated(this, null, listOf(SimpleGrantedAuthority("ROLE_${role.name}")))

    companion object {
        private const val serialVersionUID = 1L

        /** The principal of the current request, or null when not fully signed in. */
        fun currentOrNull(): ErikaPrincipal? = SecurityContextHolder.getContext().authentication?.principal as? ErikaPrincipal

        fun current(): ErikaPrincipal = checkNotNull(currentOrNull()) { "No signed-in user in this request" }
    }
}
