package de.erika.platform.web

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Result of [Idempotency.once]: the resource and whether this request created it. */
data class IdempotentResult(
    val resourceId: UUID,
    val created: Boolean,
)

/**
 * `Idempotency-Key` for POST requests that create or send something (Software Design §6).
 *
 * The key is recorded in the request's tenant transaction before the action runs. A second
 * request with the same key waits for the first one's transaction (unique key), then gets the
 * resource the first one created instead of acting again. If the first request failed, its
 * transaction rolled back and took the key with it, so a retry runs normally.
 *
 * A key belongs to one operation: reusing it for another operation is rejected.
 */
@Component
class Idempotency(
    private val jdbc: JdbcClient,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun once(
        workspaceId: UUID,
        key: String,
        operation: String,
        action: () -> UUID,
    ): IdempotentResult {
        val clean = key.trim()
        if (clean.isEmpty() || clean.length > MAX_KEY) {
            throw ErikaException(
                "IDEMPOTENCY_KEY_INVALID",
                HttpStatus.BAD_REQUEST,
                "The Idempotency-Key header must have 1 to $MAX_KEY characters.",
            )
        }
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO idempotency_key (workspace_id, key, operation) VALUES (:ws, :key, :op)
                    ON CONFLICT (workspace_id, key) DO NOTHING
                    """.trimIndent(),
                ).param("ws", workspaceId)
                .param("key", clean)
                .param("op", operation)
                .update() == 1
        if (!inserted) {
            val (op, resource) =
                jdbc
                    .sql("SELECT operation, resource_id FROM idempotency_key WHERE workspace_id = :ws AND key = :key")
                    .param("ws", workspaceId)
                    .param("key", clean)
                    .query { rs, _ -> rs.getString(1) to rs.getObject(2, UUID::class.java) }
                    .single()
            if (op != operation || resource == null) {
                throw ErikaException(
                    "IDEMPOTENCY_KEY_REUSED",
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "This Idempotency-Key was already used for another request.",
                )
            }
            return IdempotentResult(resource, created = false)
        }
        val id = action()
        jdbc
            .sql("UPDATE idempotency_key SET resource_id = :id WHERE workspace_id = :ws AND key = :key")
            .param("id", id)
            .param("ws", workspaceId)
            .param("key", clean)
            .update()
        return IdempotentResult(id, created = true)
    }

    private companion object {
        const val MAX_KEY = 200
    }
}

/** Optimistic locking with ETag / If-Match on drafts and master data (Software Design §6). */
object Etags {
    fun of(version: Int): String = "\"$version\""

    /** The version in an If-Match header; 428 if missing, 412 if unreadable. */
    fun required(ifMatch: String?): Int {
        if (ifMatch.isNullOrBlank()) {
            throw ErikaException("PRECONDITION_REQUIRED", HttpStatus.PRECONDITION_REQUIRED, "Send If-Match with the ETag you received.")
        }
        return ifMatch
            .trim()
            .removePrefix("W/")
            .trim('"')
            .toIntOrNull() ?: throw conflict()
    }

    fun conflict() =
        ErikaException(
            "VERSION_CONFLICT",
            HttpStatus.PRECONDITION_FAILED,
            "Someone else changed this in the meantime. Reload and apply your change again.",
        )
}
