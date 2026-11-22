package de.erika.intake

import de.erika.identity.WorkspaceCreated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.UUID

/**
 * Intake email addresses (IN-01): `<token>@<intake domain>`, one per workspace. The token
 * is random (about 100 bits) and can't be guessed from the workspace.
 */
@Service
internal class IntakeAddresses(
    private val repository: IntakeRepository,
    private val props: IntakeProperties,
) {
    private val random = SecureRandom()

    /** Every new workspace gets its address in the sign-up transaction. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: WorkspaceCreated) {
        ensureAddress(event.workspaceId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun addressOf(workspaceId: UUID): String = "${ensureAddress(workspaceId)}@${props.mailDomain}"

    /**
     * The workspace an intake address belongs to, or null. Accepts `token+anything@domain`
     * (sub-addressing) and ignores case; other domains are unknown.
     */
    fun workspaceFor(recipient: String): UUID? {
        val address = recipient.trim().trim('<', '>').lowercase()
        val local = address.substringBeforeLast('@', missingDelimiterValue = "")
        val domain = address.substringAfterLast('@', missingDelimiterValue = "")
        if (local.isEmpty() || domain != props.mailDomain.lowercase()) return null
        val token = local.substringBefore('+')
        if (!TOKEN.matches(token)) return null
        return repository.workspaceForToken(token)
    }

    private fun ensureAddress(workspaceId: UUID): String {
        repository.addressToken(workspaceId)?.let { return it }
        repository.insertAddress(workspaceId, newToken())
        return repository.addressToken(workspaceId)!!
    }

    /** 20 characters from [a-z0-9]: lowercase, so it survives mail clients that change case. */
    private fun newToken(): String = (1..TOKEN_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    private companion object {
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        const val TOKEN_LENGTH = 20
        val TOKEN = Regex("^[a-z0-9]{16,40}$")
    }
}
