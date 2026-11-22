package de.erika.platform.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Correlation ID per request: takes a well-formed `X-Request-Id` from the proxy or
 * generates one, puts it into the logging MDC and echoes it in the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(HEADER)?.takeIf { VALID.matches(it) } ?: UUID.randomUUID().toString()
        response.setHeader(HEADER, requestId)
        MDC.putCloseable(MDC_KEY, requestId).use { filterChain.doFilter(request, response) }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
        private val VALID = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}
