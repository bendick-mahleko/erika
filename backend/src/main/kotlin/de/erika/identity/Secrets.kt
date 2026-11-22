package de.erika.identity

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Argon2id password hashing (NF-02) with the OWASP minimum parameters
 * (19 MiB memory, 2 iterations, parallelism 1). The parameters are part of each
 * hash, so they can be raised later without breaking existing passwords.
 */
@Component
class PasswordHasher {
    private val encoder = Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, 1, MEMORY_KIB, ITERATIONS)

    /** Hash of a fixed string, checked against when the user doesn't exist (same timing). */
    private val dummyHash = encoder.encode("timing-equaliser")!!

    fun hash(raw: String): String = encoder.encode(raw)!!

    fun matches(
        raw: String,
        hash: String?,
    ): Boolean {
        if (hash == null) {
            encoder.matches(raw, dummyHash)
            return false
        }
        return encoder.matches(raw, hash)
    }

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val MEMORY_KIB = 19_456
        const val ITERATIONS = 2
    }
}

/**
 * Encrypts TOTP secrets at rest with AES-256-GCM. The key comes from configuration
 * (`erika.identity.totp-encryption-key`, Base64, 32 bytes) and is kept out of the
 * database, so a database dump alone doesn't reveal second factors.
 * Format: `v1:` + Base64(12-byte nonce || ciphertext+tag).
 */
class TotpSecretCipher(
    keyBase64: String,
) {
    private val key =
        Base64.getDecoder().decode(keyBase64).let {
            require(it.size == KEY_BYTES) { "erika.identity.totp-encryption-key must be 32 bytes (Base64)" }
            SecretKeySpec(it, "AES")
        }
    private val random = SecureRandom()

    fun encrypt(secret: ByteArray): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce)) }
        return PREFIX + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(secret))
    }

    fun decrypt(stored: String): ByteArray {
        require(stored.startsWith(PREFIX)) { "Unknown TOTP secret format" }
        val data = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, NONCE_BYTES))
            }
        return cipher.doFinal(data, NONCE_BYTES, data.size - NONCE_BYTES)
    }

    private companion object {
        const val PREFIX = "v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Random tokens for links and cookies; only their SHA-256 is stored. */
object Tokens {
    private val random = SecureRandom()

    fun newToken(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    /** Recovery code in groups for readability, e.g. `7KQ4-M2XD-9PZA` (Crockford-like alphabet, no 0/O/1/I). */
    fun newRecoveryCode(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        return (1..3).joinToString("-") { (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
    }

    fun normaliseRecoveryCode(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }
}
