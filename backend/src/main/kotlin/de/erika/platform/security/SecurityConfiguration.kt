package de.erika.platform.security

import de.erika.platform.runtime.RuntimeInstance
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authorization.AuthorityAuthorizationManager
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfException
import tools.jackson.databind.json.JsonMapper

/**
 * HTTP security for the API (Software Design §7):
 * - server-side sessions (Spring Session JDBC); the SecurityContext lives in the session,
 * - CSRF tokens for every state-changing request (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`),
 * - everything requires a fully signed-in user except health and the sign-in flow,
 * - role checks per endpoint with `@PreAuthorize`; tax advisors can never change data (EX-02),
 * - errors as RFC 9457 problem details with a stable code.
 */
@Configuration(proxyBeanMethods = false)
@Profile(RuntimeInstance.PROFILE_API)
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration {
    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        contextRepository: SecurityContextRepository,
        json: JsonMapper,
    ): SecurityFilterChain {
        val webRoles =
            AuthorityAuthorizationManager.hasAnyRole<org.springframework.security.web.access.intercept.RequestAuthorizationContext>(
                "OWNER",
                "MEMBER",
                "ADVISOR",
            )
        http
            // The mail server's internal endpoints authenticate with a shared secret, not a session.
            .csrf {
                it.spa()
                it.ignoringRequestMatchers("/internal/intake/**", "/webhooks/**")
            }.securityContext { it.securityContextRepository(contextRepository) }
            .sessionManagement { it.sessionFixation { fixation -> fixation.changeSessionId() } }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .anonymous { it.disable() }
            .authorizeHttpRequests {
                it
                    .requestMatchers(HttpMethod.GET, "/api/v1/health")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/**", "/error")
                    .permitAll()
                    // MARA app status before sign-in (api/mobile.yaml, ADR 0038): versions and server time only.
                    .requestMatchers(HttpMethod.GET, "/api/v1/mobile/status")
                    .permitAll()
                    // Postfix → API (api/internal.yaml): checked against the shared secret in the controller.
                    // Not routed by the reverse proxy; reachable only on the internal network.
                    .requestMatchers(HttpMethod.POST, "/internal/intake/**")
                    .permitAll()
                    // Provider callbacks (api/webhooks.yaml): checked against their shared secret in the controller.
                    .requestMatchers(HttpMethod.POST, "/webhooks/mail", "/webhooks/stripe")
                    .permitAll()
                    // Actuator runs on the separate management port, which the reverse proxy
                    // doesn't route (container health checks, Prometheus scraping).
                    .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/health", "/actuator/info", "/actuator/prometheus")
                    .permitAll()
                    // Read-only for tax advisors: every state-changing request needs OWNER or MEMBER,
                    // with exactly three exceptions that change no business data (EX-02, ADR 0032).
                    // Web roles only: technicians work in the MARA app and have no web session (ADR 0039);
                    // this rule is the second line of defence behind the sign-in check.
                    .requestMatchers(HttpMethod.GET, "/api/v1/**")
                    .access(webRoles)
                    // Feedback to the ERIKA team changes no workspace data: every role (ADR 0030).
                    .requestMatchers(HttpMethod.POST, "/api/v1/feedback")
                    .access(webRoles)
                    // Tax advisors comment on documents and export them (EX-01, EX-02).
                    .requestMatchers(HttpMethod.POST, "/api/v1/documents/*/comments", "/api/v1/exports")
                    .access(webRoles)
                    .requestMatchers("/api/v1/**")
                    .access(AuthorityAuthorizationManager.hasAnyRole("OWNER", "MEMBER"))
                    .anyRequest()
                    .denyAll()
            }.exceptionHandling {
                it
                    .authenticationEntryPoint { _, response, _ ->
                        problem(json, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Sign-in required.")
                    }.accessDeniedHandler { _, response, ex ->
                        if (ex is CsrfException) {
                            problem(json, response, HttpStatus.FORBIDDEN, "CSRF_TOKEN_INVALID", "Missing or invalid CSRF token.")
                        } else {
                            problem(json, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role does not allow this action.")
                        }
                    }
            }
        return http.build()
    }

    private fun problem(
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
            mapOf("type" to "about:blank", "title" to status.reasonPhrase, "status" to status.value(), "detail" to detail, "code" to code),
        )
    }
}
