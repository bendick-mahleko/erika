package de.erika.identity.web

import de.erika.identity.MobileAuth
import de.erika.identity.TokenCheck
import de.erika.platform.runtime.RuntimeInstance
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authorization.AuthorityAuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Security for the MARA app (all paths under `/api/v1/mobile/`, ADR 0037), a filter chain of its own that
 * runs before the web chain:
 * - stateless: bearer access tokens, no session, no cookies and therefore no CSRF token;
 * - the principal (user, workspace, current role, device) is built per request from the
 *   token, so `TenantTransactionFilter`, `WorkspaceAccessInterceptor` and
 *   `EntitlementInterceptor` apply unchanged;
 * - public: the status before sign-in, device sign-in, 2FA set-up and token renewal;
 * - everything else needs a device of an OWNER, MEMBER or TECHNICIAN (tax advisors don't use
 *   the app, ADR 0039).
 */
@Configuration(proxyBeanMethods = false)
@Profile(RuntimeInstance.PROFILE_API)
internal class MobileSecurityConfiguration {
    @Bean
    @Order(1)
    fun mobileSecurityFilterChain(
        http: HttpSecurity,
        auth: MobileAuth,
        json: JsonMapper,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/v1/mobile/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { it.securityContextRepository(RequestAttributeSecurityContextRepository()) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .anonymous { it.disable() }
            .addFilterBefore(MobileTokenFilter(auth, json), AuthorizationFilter::class.java)
            .authorizeHttpRequests {
                it
                    .requestMatchers(HttpMethod.GET, STATUS)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, *PUBLIC_POSTS)
                    .permitAll()
                    .requestMatchers("/api/v1/mobile/**")
                    .access(AuthorityAuthorizationManager.hasAnyRole("OWNER", "MEMBER", "TECHNICIAN"))
                    .anyRequest()
                    .denyAll()
            }.exceptionHandling {
                it
                    .authenticationEntryPoint { _, response, _ ->
                        problem(json, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Sign-in required.")
                    }.accessDeniedHandler { _, response, _ ->
                        problem(json, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role does not allow this action.")
                    }
            }
        return http.build()
    }

    companion object {
        const val STATUS = "/api/v1/mobile/status"

        /** Reachable without a token (`security: []` in api/mobile.yaml). */
        val PUBLIC_POSTS =
            arrayOf("/api/v1/mobile/auth/device-login", "/api/v1/mobile/auth/refresh", "/api/v1/mobile/auth/totp-enrolment")

        fun problem(
            json: JsonMapper,
            response: HttpServletResponse,
            status: HttpStatus,
            code: String,
            detail: String,
        ) {
            response.status = status.value()
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            json.writeValue(
                response.outputStream,
                mapOf(
                    "type" to "about:blank",
                    "title" to status.reasonPhrase,
                    "status" to status.value(),
                    "detail" to detail,
                    "code" to code,
                ),
            )
        }
    }
}

/**
 * Turns `Authorization: Bearer <access token>` into the request's principal. Public
 * operations ignore the header. The answers tell the app what to do:
 * `TOKEN_EXPIRED` → renew with the refresh token; `DEVICE_REVOKED` → delete local data;
 * `UNAUTHORIZED` → sign in again.
 */
internal class MobileTokenFilter(
    private val auth: MobileAuth,
    private val json: JsonMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path == MobileSecurityConfiguration.STATUS || path in MobileSecurityConfiguration.PUBLIC_POSTS
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith(BEARER, ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }
        val token = header.substring(BEARER.length).trim()
        val check = if (TOKEN.matches(token)) auth.authenticate(token, request.getHeader("X-App-Version")) else TokenCheck.Unknown
        when (check) {
            is TokenCheck.Valid -> {
                val context = SecurityContextHolder.createEmptyContext()
                context.authentication = check.principal.toAuthentication()
                SecurityContextHolder.setContext(context)
                filterChain.doFilter(request, response)
            }

            TokenCheck.Expired -> {
                unauthorized(response, "TOKEN_EXPIRED", "The access token has expired. Renew it with the refresh token.")
            }

            TokenCheck.Revoked -> {
                unauthorized(response, "DEVICE_REVOKED", "This device has been signed out.")
            }

            TokenCheck.Unknown -> {
                unauthorized(response, "UNAUTHORIZED", "Sign-in required.")
            }
        }
    }

    private fun unauthorized(
        response: HttpServletResponse,
        code: String,
        detail: String,
    ) = MobileSecurityConfiguration.problem(json, response, HttpStatus.UNAUTHORIZED, code, detail)

    private companion object {
        const val BEARER = "Bearer "

        /** [de.erika.identity.Tokens.newToken]: 32 random bytes, Base64url without padding. */
        val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
