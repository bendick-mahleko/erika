package de.erika.platform.web

import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RequestIdFilterTest {
    private val filter = RequestIdFilter()

    @Test
    fun `keeps a well-formed incoming request id and exposes it in the MDC`() {
        val request = MockHttpServletRequest().apply { addHeader(RequestIdFilter.HEADER, "abc-123") }
        val response = MockHttpServletResponse()
        var seenInMdc: String? = null

        filter.doFilter(request, response, MockFilterChain(NoopServlet { seenInMdc = MDC.get(RequestIdFilter.MDC_KEY) }))

        assertEquals("abc-123", response.getHeader(RequestIdFilter.HEADER))
        assertEquals("abc-123", seenInMdc)
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "MDC must be cleared after the request")
    }

    @Test
    fun `replaces a malformed request id`() {
        val request = MockHttpServletRequest().apply { addHeader(RequestIdFilter.HEADER, "bad id\r\ninjected") }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        val id = response.getHeader(RequestIdFilter.HEADER)
        assertNotEquals("bad id\r\ninjected", id)
        assertEquals(36, id?.length, "expected a generated UUID")
    }

    private class NoopServlet(
        private val onRequest: () -> Unit,
    ) : jakarta.servlet.http.HttpServlet() {
        override fun service(
            req: jakarta.servlet.ServletRequest,
            res: jakarta.servlet.ServletResponse,
        ) = onRequest()
    }
}
