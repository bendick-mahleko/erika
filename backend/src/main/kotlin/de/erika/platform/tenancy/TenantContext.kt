package de.erika.platform.tenancy

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Scopes the current database transaction to a workspace (and user) for row-level
 * security: `set_config(..., true)` is `SET LOCAL`, so the setting ends with the
 * transaction and never leaks to the next user of the pooled connection.
 *
 * Must be called inside a transaction; outside one, `SET LOCAL` would have no effect,
 * so this fails fast instead.
 */
@Component
class TenantContext(
    private val jdbc: JdbcClient,
) {
    fun enterWorkspace(workspaceId: UUID) = set(WORKSPACE, workspaceId)

    fun enterUser(userId: UUID) = set(USER, userId)

    private fun set(
        name: String,
        value: UUID,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Tenant scope ($name) needs an active transaction"
        }
        jdbc
            .sql("SELECT set_config(:name, :value, true)")
            .param("name", name)
            .param("value", value.toString())
            .query(String::class.java)
            .single()
    }

    companion object {
        const val WORKSPACE = "app.workspace_id"
        const val USER = "app.user_id"
    }
}
