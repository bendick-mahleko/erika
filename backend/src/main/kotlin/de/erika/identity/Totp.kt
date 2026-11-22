package de.erika.identity

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Clock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords (RFC 6238; HMAC-SHA1, 6 digits, 30 s steps), the
 * parameters every common authenticator app supports.
 */
class Totp(
    private val clock: Clock = Clock.systemUTC(),
) {
    fun currentStep(): Long = clock.instant().epochSecond / STEP_SECONDS

    /** The code for [secret] at time step [step]. */
    fun code(
        secret: ByteArray,
        step: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return (binary % MODULUS).toString().padStart(DIGITS, '0')
    }

    /**
     * Returns the matching time step if [code] is valid now (±1 step for clock drift)
     * and newer than [lastUsedStep] (a code can be used only once), else null.
     */
    fun verify(
        secret: ByteArray,
        code: String,
        lastUsedStep: Long?,
    ): Long? {
        if (!CODE.matches(code)) return null
        val now = currentStep()
        return (now - WINDOW..now + WINDOW)
            .filter { lastUsedStep == null || it > lastUsedStep }
            .firstOrNull { constantTimeEquals(code(secret, it), code) }
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ) = java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val STEP_SECONDS = 30L
        const val DIGITS = 6
        private const val MODULUS = 1_000_000
        private const val WINDOW = 1L
        private const val SECRET_BYTES = 20
        private val CODE = Regex("^[0-9]{6}$")

        fun newSecret(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SECRET_BYTES).also(random::nextBytes)

        /** `otpauth://` URI for QR codes (Key Uri Format used by authenticator apps). */
        fun otpauthUri(
            secret: ByteArray,
            account: String,
            issuer: String = "ERIKA",
        ): String {
            val label =
                java.net.URLEncoder
                    .encode("$issuer:$account", Charsets.UTF_8)
                    .replace("+", "%20")
            return "otpauth://totp/$label?secret=${Base32.encode(secret)}&issuer=$issuer&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
        }
    }
}

/** RFC 4648 Base32 without padding, as used for TOTP secrets. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.uppercase().filterNot { it == '=' || it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "Not a Base32 character" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
