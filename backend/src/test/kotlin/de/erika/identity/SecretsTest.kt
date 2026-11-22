package de.erika.identity

import de.erika.platform.security.RateLimiter
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TotpTest {
    // RFC 6238 Appendix B, SHA-1 secret; the RFC lists 8 digits, authenticator apps use the last 6.
    private val rfcSecret = "12345678901234567890".toByteArray()

    @Test
    fun `matches the RFC 6238 test vectors`() {
        val totp = Totp()
        mapOf(59L to "287082", 1111111109L to "081804", 1111111111L to "050471", 1234567890L to "005924", 2000000000L to "279037")
            .forEach { (time, expected) -> assertEquals(expected, totp.code(rfcSecret, time / 30), "t=$time") }
    }

    @Test
    fun `accepts the current and adjacent steps once, rejects older ones`() {
        val clock = Clock.fixed(Instant.ofEpochSecond(1_000_000_020), ZoneOffset.UTC)
        val totp = Totp(clock)
        val now = totp.currentStep()

        assertEquals(now, totp.verify(rfcSecret, totp.code(rfcSecret, now), null))
        assertEquals(now - 1, totp.verify(rfcSecret, totp.code(rfcSecret, now - 1), null))
        assertEquals(now + 1, totp.verify(rfcSecret, totp.code(rfcSecret, now + 1), null))
        assertNull(totp.verify(rfcSecret, totp.code(rfcSecret, now - 2), null), "outside the window")
        assertNull(totp.verify(rfcSecret, totp.code(rfcSecret, now), now), "replay of the same step")
        assertNull(totp.verify(rfcSecret, "12345", null), "malformed")
    }

    @Test
    fun `otpauth URI carries issuer, account and parameters`() {
        val uri = Totp.otpauthUri(rfcSecret, "erika@example.de")
        assertTrue(uri.startsWith("otpauth://totp/ERIKA%3Aerika%40example.de?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        assertTrue(uri.endsWith("&issuer=ERIKA&algorithm=SHA1&digits=6&period=30"))
    }
}

class Base32Test {
    @Test
    fun `RFC 4648 vectors without padding`() {
        mapOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6", "foob" to "MZXW6YQ", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI")
            .forEach { (plain, encoded) ->
                assertEquals(encoded, Base32.encode(plain.toByteArray()))
                assertEquals(plain, String(Base32.decode(encoded)))
            }
        assertEquals("foobar", String(Base32.decode("mzxw 6ytb oi======")))
        assertFailsWith<IllegalArgumentException> { Base32.decode("0189") }
    }
}

class TotpSecretCipherTest {
    private val cipher = TotpSecretCipher(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))

    @Test
    fun `round trip with a fresh nonce each time`() {
        val secret = Totp.newSecret()
        val a = cipher.encrypt(secret)
        val b = cipher.encrypt(secret)
        assertNotEquals(a, b)
        assertContentEquals(secret, cipher.decrypt(a))
        assertContentEquals(secret, cipher.decrypt(b))
    }

    @Test
    fun `tampered ciphertext and wrong keys are rejected`() {
        val stored = cipher.encrypt(Totp.newSecret())
        val bytes = Base64.getDecoder().decode(stored.removePrefix("v1:")).also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertFailsWith<Exception> { cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)) }
        val other = TotpSecretCipher(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        assertFailsWith<Exception> { other.decrypt(stored) }
        assertFailsWith<IllegalArgumentException> { TotpSecretCipher(Base64.getEncoder().encodeToString(ByteArray(16))) }
    }
}

class TokensTest {
    @Test
    fun `recovery codes are readable and normalise for comparison`() {
        val code = Tokens.newRecoveryCode()
        assertTrue(Regex("^[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$").matches(code), code)
        assertEquals(Tokens.normaliseRecoveryCode(code), Tokens.normaliseRecoveryCode(code.lowercase().replace("-", " ")))
    }

    @Test
    fun `tokens are url-safe and unique`() {
        val tokens = (1..100).map { Tokens.newToken() }.toSet()
        assertEquals(100, tokens.size)
        assertTrue(tokens.all { Regex("^[A-Za-z0-9_-]{43}$").matches(it) })
    }
}

class PasswordHasherTest {
    private val hasher = PasswordHasher()

    @Test
    fun `argon2id with salt, verifies only the right password`() {
        val a = hasher.hash("correct horse battery staple")
        val b = hasher.hash("correct horse battery staple")
        assertTrue(a.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"), a)
        assertNotEquals(a, b)
        assertTrue(hasher.matches("correct horse battery staple", a))
        assertFalse(hasher.matches("Correct horse battery staple", a))
        assertFalse(hasher.matches("anything", null))
    }
}

class RateLimiterTest {
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    @Test
    fun `allows the limit per key and window, then recovers`() {
        val clock = MutableClock(Instant.parse("2026-11-22T10:00:00Z"))
        val limiter = RateLimiter(3, Duration.ofMinutes(1), clock)
        repeat(3) { assertTrue(limiter.tryAcquire("ip-1")) }
        assertFalse(limiter.tryAcquire("ip-1"))
        assertTrue(limiter.tryAcquire("ip-2"), "keys are independent")
        clock.now = clock.now.plusSeconds(61)
        assertTrue(limiter.tryAcquire("ip-1"), "window slid")
    }
}
