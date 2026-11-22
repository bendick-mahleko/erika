package de.erika.platform

import de.erika.support.ApiIntegrationTest
import de.erika.support.TestDatabase
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Database side of tenant isolation (Software Design §7, NF-03), checked on the migrated
 * schema, so every future migration is held to the same rules ("migration lint").
 */
class TenancyDatabaseIT : ApiIntegrationTest() {
    /**
     * Tables that are deliberately not tenant-scoped, with the reason. Everything else in
     * schema `erika` is a tenant table and must follow the rules below.
     */
    private val systemTables =
        mapOf(
            "flyway_schema_history" to "Flyway bookkeeping",
            "job" to "worker serves all workspaces; jobs carry workspace_id and run with app.workspace_id set",
            "system_heartbeat" to "liveness of background components",
            "app_user" to "a user can belong to several workspaces; roles live in membership",
            "user_token" to "belongs to app_user (email verification)",
            "recovery_code" to "belongs to app_user (2FA)",
            "known_device" to "belongs to app_user (new-device notification)",
            "spring_session" to "sessions exist before a workspace is chosen",
            "spring_session_attributes" to "part of spring_session",
            "workspace" to "the tenant itself: RLS on id (checked separately)",
        )

    @Test
    fun `every tenant table has workspace_id NOT NULL, an index on it, RLS and a policy`() {
        val problems = mutableListOf<String>()
        TestDatabase.asOwner { c ->
            for (table in tables(c) - systemTables.keys) {
                if (!c.hasNotNullColumn(table, "workspace_id")) problems += "$table: no workspace_id NOT NULL"
                if (!c.hasIndexLeadingWith(table, "workspace_id")) problems += "$table: no index starting with workspace_id"
                if (!c.rlsEnabled(table)) problems += "$table: row-level security not enabled"
                val policies = c.policies(table)
                if (policies.isEmpty()) problems += "$table: no RLS policy"
                if (policies.none { it.contains("current_workspace_id()") }) problems += "$table: no policy uses current_workspace_id()"
            }
            if (!c.rlsEnabled("workspace") || c.policies("workspace").none { it.contains("current_workspace_id()") }) {
                problems += "workspace: needs RLS with a policy on current_workspace_id()"
            }
            val unknownSystem = systemTables.keys - tables(c)
            if (unknownSystem.isNotEmpty()) problems += "system table list names missing tables: $unknownSystem"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `as the app role without app_workspace_id, tenant tables return no rows`() {
        val user = accounts.enrolled("rls") // makes sure the identity and audit tables have rows
        TestDatabase.update(
            """
            INSERT INTO stored_object (workspace_id, sha256, size_bytes, media_type, storage_key, retention_until, object_lock)
            VALUES (?, repeat('a', 64), 0, 'application/xml', 'ws/rls/aaa', '2034-12-31', 'NONE')
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            user.workspaceId,
        )
        TestDatabase.asApp { c ->
            val tenantTables = tables(c) - systemTables.keys + "workspace"
            for (table in tenantTables) {
                val total = TestDatabase.asOwner { owner -> owner.count(table) }
                assertTrue(total > 0, "$table should have rows for this check")
                assertEquals(0, c.count(table), "$table visible without app.workspace_id")
            }
        }
    }

    @Test
    fun `the app role cannot change or delete audit entries`() {
        accounts.enrolled("audit-rights")
        TestDatabase.asApp { c ->
            val ws =
                TestDatabase.asOwner {
                    it.createStatement().executeQuery("SELECT workspace_id FROM audit_log LIMIT 1").run {
                        next()
                        getString(1)
                    }
                }
            c.autoCommit = false
            c.createStatement().execute("SELECT set_config('app.workspace_id', '$ws', true)")
            assertFailsWith<SQLException> { c.createStatement().executeUpdate("UPDATE audit_log SET action = 'x'") }
            c.rollback()
            c.createStatement().execute("SELECT set_config('app.workspace_id', '$ws', true)")
            assertFailsWith<SQLException> { c.createStatement().executeUpdate("DELETE FROM audit_log") }
            c.rollback()
        }
    }

    @Test
    fun `a workspace cannot insert rows for another workspace`() {
        val a = accounts.registered("insert-a")
        val b = accounts.registered("insert-b")
        TestDatabase.asApp { c ->
            c.autoCommit = false
            c.createStatement().execute("SELECT set_config('app.workspace_id', '${a.workspaceId}', true)")
            val ex =
                assertFailsWith<SQLException> {
                    c.createStatement().executeUpdate(
                        "INSERT INTO membership (id, workspace_id, user_id, role) VALUES (gen_random_uuid(), '${b.workspaceId}', '${a.userId}', 'OWNER')",
                    )
                }
            assertTrue(ex.message!!.contains("row-level security"), ex.message)
            c.rollback()
        }
    }

    private fun tables(c: Connection): Set<String> =
        c
            .prepareStatement("SELECT tablename FROM pg_tables WHERE schemaname = 'erika'")
            .executeQuery()
            .use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() }

    private fun Connection.hasNotNullColumn(
        table: String,
        column: String,
    ) = prepareStatement(
        "SELECT is_nullable = 'NO' FROM information_schema.columns WHERE table_schema = 'erika' AND table_name = ? AND column_name = ?",
    ).apply {
        setString(1, table)
        setString(2, column)
    }.executeQuery()
        .use { it.next() && it.getBoolean(1) }

    private fun Connection.hasIndexLeadingWith(
        table: String,
        column: String,
    ) = prepareStatement(
        """
        SELECT EXISTS (
          SELECT 1 FROM pg_index i
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = i.indkey[0]
           WHERE n.nspname = 'erika' AND t.relname = ? AND a.attname = ?)
        """.trimIndent(),
    ).apply {
        setString(1, table)
        setString(2, column)
    }.executeQuery()
        .use { it.next() && it.getBoolean(1) }

    private fun Connection.rlsEnabled(table: String) =
        prepareStatement(
            "SELECT relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'erika' AND c.relname = ?",
        ).apply { setString(1, table) }
            .executeQuery()
            .use { it.next() && it.getBoolean(1) }

    private fun Connection.policies(table: String): List<String> =
        prepareStatement(
            "SELECT coalesce(qual, '') || ' ' || coalesce(with_check, '') FROM pg_policies WHERE schemaname = 'erika' AND tablename = ?",
        ).apply { setString(1, table) }
            .executeQuery()
            .use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }

    private fun Connection.count(table: String): Long =
        try {
            createStatement().executeQuery("SELECT count(*) FROM $table").use {
                it.next()
                it.getLong(1)
            }
        } catch (e: SQLException) {
            fail("count($table): ${e.message}")
        }
}
