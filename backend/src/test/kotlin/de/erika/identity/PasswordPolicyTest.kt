package de.erika.identity

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordPolicyTest {
    private fun sha1(text: String) = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(text.toByteArray()))

    @Test
    fun `only the hash prefix is sent, a listed suffix with a count is breached, padding entries are not`() {
        val breached = "password1234"
        val prefixes = mutableListOf<String>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/range/") { ex ->
                    prefixes += ex.requestURI.path.substringAfterLast('/')
                    val body = "${sha1(breached).drop(5)}:42\r\n${sha1("padding entry").drop(5)}:0\r\n"
                    ex.sendResponseHeaders(200, body.length.toLong())
                    ex.responseBody.use { it.write(body.toByteArray()) }
                }
                start()
            }
        try {
            val policy = PasswordPolicy(PasswordProperties(breachApi = "http://127.0.0.1:${server.address.port}"))
            assertTrue(policy.breached(breached))
            assertEquals(listOf(sha1(breached).take(5)), prefixes, "never more than five characters of the hash")
            assertFalse(policy.breached("a long and unusual passphrase 7f3"))
            val e = assertFailsWith<de.erika.platform.web.ErikaException> { policy.check(breached, "x@example.test") }
            assertEquals("PASSWORD_BREACHED", e.code)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an unreachable breach service doesn't block (fail open), the other rules still apply`() {
        val policy = PasswordPolicy(PasswordProperties(breachApi = "http://127.0.0.1:9"))
        assertFalse(policy.breached("password1234"))
        assertEquals(
            "WEAK_PASSWORD",
            assertFailsWith<de.erika.platform.web.ErikaException> {
                policy.check("me@firma.de-secret", "me@firma.de")
            }.code,
        )
        assertEquals("WEAK_PASSWORD", assertFailsWith<de.erika.platform.web.ErikaException> { policy.check("kurz", "a@b.de") }.code)
    }
}
