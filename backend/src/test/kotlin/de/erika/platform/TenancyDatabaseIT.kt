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
            "stripe_event" to "Stripe webhook events arrive without a workspace; the worker maps them (ADR 0033)",
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
            VALUES (?, repeat('a', 64), 1, 'application/xml', 'ws/rls/aaa', '2034-12-31', 'NONE')
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            user.workspaceId,
        )
        // One row in every Stage 5 table (intake_address is created at sign-up).
        val doc = java.util.UUID.randomUUID()
        val intake = java.util.UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO document (id, workspace_id, direction, sha256, media_type, size_bytes, received_via) VALUES (?, ?, 'IN', repeat('a', 64), 'application/xml', 0, 'UPLOAD')",
            doc,
            user.workspaceId,
        )
        TestDatabase.update("INSERT INTO parsed_invoice (document_id, workspace_id, data) VALUES (?, ?, '{}')", doc, user.workspaceId)
        TestDatabase.update(
            """
            INSERT INTO validation_result (id, workspace_id, document_id, artefact_version, level, applicable, messages, checked_at)
            VALUES (gen_random_uuid(), ?, ?, 'test', 'VALID', true, '[]', now())
            """.trimIndent(),
            user.workspaceId,
            doc,
        )
        TestDatabase.update(
            "INSERT INTO intake (id, workspace_id, source, status) VALUES (?, ?, 'UPLOAD', 'DONE')",
            intake,
            user.workspaceId,
        )
        TestDatabase.update(
            "INSERT INTO status_event (id, workspace_id, document_id, to_status) VALUES (gen_random_uuid(), ?, ?, 'CHECKED')",
            user.workspaceId,
            doc,
        )
        TestDatabase.update(
            """
            INSERT INTO derived_object (workspace_id, document_id, kind, idx, storage_key, sha256, media_type, size_bytes)
            VALUES (?, ?, 'VIEW_PDF', 0, 'k', repeat('b', 64), 'application/pdf', 1)
            """.trimIndent(),
            user.workspaceId,
            doc,
        )
        TestDatabase.update(
            "INSERT INTO intake_item (id, workspace_id, intake_id, seq, file_name, outcome) VALUES (gen_random_uuid(), ?, ?, 0, 'x.xml', 'IMPORTED')",
            user.workspaceId,
            intake,
        )
        TestDatabase.update(
            "INSERT INTO feedback (id, workspace_id, user_id, category, message) VALUES (gen_random_uuid(), ?, ?, 'IDEA', 'x')",
            user.workspaceId,
            user.userId,
        )
        // Stage 8: creator tables.
        val draft = java.util.UUID.randomUUID()
        TestDatabase.update("INSERT INTO idempotency_key (workspace_id, key, operation) VALUES (?, 'rls-key', 'test')", user.workspaceId)
        TestDatabase.update("INSERT INTO invoice_settings (workspace_id) VALUES (?) ON CONFLICT DO NOTHING", user.workspaceId)
        TestDatabase.update("INSERT INTO party (id, workspace_id, name) VALUES (gen_random_uuid(), ?, 'Kunde')", user.workspaceId)
        TestDatabase.update(
            "INSERT INTO article (id, workspace_id, name, net_price) VALUES (gen_random_uuid(), ?, 'Artikel', 1)",
            user.workspaceId,
        )
        TestDatabase.update(
            "INSERT INTO number_sequence (workspace_id, sequence_key, next_value) VALUES (?, 'RE-{N}', 1)",
            user.workspaceId,
        )
        TestDatabase.update(
            """
            INSERT INTO invoice (id, workspace_id, number, kind, type_code, format, document_id, sha256, file_name, media_type,
                                 issue_date, buyer_name, currency, net, tax, gross)
            VALUES (?, ?, 'RLS-1', 'INVOICE', '380', 'XRECHNUNG_CII', ?, repeat('a', 64), 'x.xml', 'application/xml',
                    current_date, 'Kunde', 'EUR', 1, 0, 1)
            """.trimIndent(),
            draft,
            user.workspaceId,
            doc,
        )
        TestDatabase.update(
            "INSERT INTO invoice_draft (id, workspace_id, state, data) VALUES (?, ?, 'ISSUED', '{}')",
            draft,
            user.workspaceId,
        )
        TestDatabase.update(
            """
            INSERT INTO invoice_delivery (id, workspace_id, invoice_id, recipient, subject, message_id)
            VALUES (gen_random_uuid(), ?, ?, 'a@example.test', 'Rechnung', ?)
            """.trimIndent(),
            user.workspaceId,
            draft,
            "<${java.util.UUID.randomUUID()}@test>",
        )
        // Stage 9: invitations, comments, DATEV settings, exports.
        TestDatabase.update(
            """
            INSERT INTO invitation (id, workspace_id, email, role, token_hash, invited_by, expires_at)
            VALUES (gen_random_uuid(), ?, 'rls@example.test', 'ADVISOR', gen_random_uuid()::text::bytea, ?, now())
            """.trimIndent(),
            user.workspaceId,
            user.userId,
        )
        TestDatabase.update(
            "INSERT INTO document_comment (id, workspace_id, document_id, author_id, body) VALUES (gen_random_uuid(), ?, ?, ?, 'x')",
            user.workspaceId,
            doc,
            user.userId,
        )
        TestDatabase.update("INSERT INTO datev_settings (workspace_id) VALUES (?) ON CONFLICT DO NOTHING", user.workspaceId)
        TestDatabase.update("INSERT INTO intake_settings (workspace_id) VALUES (?) ON CONFLICT DO NOTHING", user.workspaceId)
        TestDatabase.update(
            "INSERT INTO intake_sender (id, workspace_id, pattern) VALUES (gen_random_uuid(), ?, '@rls.example')",
            user.workspaceId,
        )
        TestDatabase.update(
            "INSERT INTO due_reminder (workspace_id, document_id, due_date) VALUES (?, ?, current_date)",
            user.workspaceId,
            doc,
        )
        TestDatabase.update(
            """
            INSERT INTO recurring_invoice (id, workspace_id, name, source_invoice_id, content, interval, next_run)
            VALUES (gen_random_uuid(), ?, 'RLS', gen_random_uuid(), '{}', 'MONTHLY', current_date)
            """.trimIndent(),
            user.workspaceId,
        )
        TestDatabase.update(
            "INSERT INTO data_export (id, workspace_id, kind, requested_by) VALUES (gen_random_uuid(), ?, 'FULL', ?)",
            user.workspaceId,
            user.userId,
        )
        // Stage 10: billing (billing_account comes with the sign-up).
        TestDatabase.update(
            """
            INSERT INTO billing_invoice (id, workspace_id, customer_workspace_id, stripe_invoice_id, erika_invoice_id, currency,
                                         net, tax, gross, paid_at)
            VALUES (gen_random_uuid(), ?, ?, 'in_rls_' || gen_random_uuid(), gen_random_uuid(), 'EUR', 1, 0, 1, now())
            """.trimIndent(),
            user.workspaceId,
            user.workspaceId,
        )
        // MARA Stage 2: sites, staff levels and profiles, checklist templates, work orders.
        val customer = java.util.UUID.randomUUID()
        val level = java.util.UUID.randomUUID()
        val order = java.util.UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO site (id, workspace_id, customer_id, name) VALUES (gen_random_uuid(), ?, ?, 'Baustelle')",
            user.workspaceId,
            customer,
        )
        TestDatabase.update(
            "INSERT INTO staff_level (id, workspace_id, name, hourly_rate) VALUES (?, ?, 'Geselle', 50)",
            level,
            user.workspaceId,
        )
        TestDatabase.update(
            "INSERT INTO staff_profile (workspace_id, user_id, staff_level_id) VALUES (?, ?, ?)",
            user.workspaceId,
            user.userId,
            level,
        )
        TestDatabase.update(
            "INSERT INTO checklist_template (id, workspace_id, name, items) VALUES (gen_random_uuid(), ?, 'Wartung', '[]')",
            user.workspaceId,
        )
        TestDatabase.update(
            "INSERT INTO work_order (id, workspace_id, customer_id, title) VALUES (?, ?, ?, 'Auftrag')",
            order,
            user.workspaceId,
            customer,
        )
        TestDatabase.update(
            "INSERT INTO work_order_assignment (workspace_id, work_order_id, user_id) VALUES (?, ?, ?)",
            user.workspaceId,
            order,
            user.userId,
        )
        // MARA Stage 3: a phone and one of its tokens.
        val device = java.util.UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO mobile_device (id, workspace_id, user_id, name, platform) VALUES (?, ?, ?, 'Telefon', 'IOS')",
            device,
            user.workspaceId,
            user.userId,
        )
        TestDatabase.update(
            "INSERT INTO mobile_token (token_hash, workspace_id, device_id, kind, expires_at) VALUES (sha256(gen_random_uuid()::text::bytea), ?, ?, 'ACCESS', now())",
            user.workspaceId,
            device,
        )
        // MARA Stage 4: the sync engine's tables.
        TestDatabase.update("INSERT INTO sync_counter (workspace_id, value) VALUES (?, 1) ON CONFLICT DO NOTHING", user.workspaceId)
        TestDatabase.update(
            "INSERT INTO change_log (workspace_id, entity, entity_id, seq) VALUES (?, 'work_order', ?, 1) ON CONFLICT DO NOTHING",
            user.workspaceId,
            order,
        )
        TestDatabase.update(
            "INSERT INTO applied_mutation (workspace_id, mutation_id, device_id, result) VALUES (?, gen_random_uuid(), ?, '{}')",
            user.workspaceId,
            device,
        )
        TestDatabase.update(
            "INSERT INTO sync_conflict (id, workspace_id, entity, entity_id, field, winner, user_id, device_id) " +
                "VALUES (gen_random_uuid(), ?, 'work_order', ?, 'status', 'DEVICE', ?, ?)",
            user.workspaceId,
            order,
            user.userId,
            device,
        )
        // MARA Stage 5: a report and its child records.
        val report = java.util.UUID.randomUUID()
        TestDatabase.update(
            "INSERT INTO report (id, workspace_id, work_order_id, created_by) VALUES (?, ?, ?, ?)",
            report,
            user.workspaceId,
            order,
            user.userId,
        )
        TestDatabase.update(
            "INSERT INTO time_entry (id, workspace_id, report_id, author_id, technician_id, kind, started_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?, 'WORK', now())",
            user.workspaceId,
            report,
            user.userId,
            user.userId,
        )
        TestDatabase.update(
            "INSERT INTO material_line (id, workspace_id, report_id, author_id, description, quantity, unit_code) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, 'Dichtung', 2, 'C62')",
            user.workspaceId,
            report,
            user.userId,
        )
        TestDatabase.update(
            "INSERT INTO aufmass_position (id, workspace_id, report_id, author_id, label, kind, length, width, result, " +
                "rule_version, unit_code) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, 'Boden', 'AREA', 4, 3, 12, 1, 'MTK')",
            user.workspaceId,
            report,
            user.userId,
        )
        TestDatabase.update(
            "INSERT INTO checklist_answer (id, workspace_id, report_id, item_key, value, author_id) VALUES (gen_random_uuid(), ?, ?, 'water', 'YES', ?)",
            user.workspaceId,
            report,
            user.userId,
        )
        // MARA Stage 7: a signature and an email of the report.
        TestDatabase.update(
            "INSERT INTO report_signature (id, workspace_id, report_id, kind, content_hash, signed_at, not_signed_reason, " +
                "verification_code, download_key_hash, created_by) " +
                "VALUES (gen_random_uuid(), ?, ?, 'NOT_SIGNED', repeat('d', 64), now(), 'abwesend', 'ABCDEFGHIJ', sha256('k'::bytea), ?)",
            user.workspaceId,
            report,
            user.userId,
        )
        TestDatabase.update(
            "INSERT INTO report_delivery (id, workspace_id, report_id, recipient, eml_sha256) " +
                "VALUES (gen_random_uuid(), ?, ?, 'a@example.test', repeat('e', 64))",
            user.workspaceId,
            report,
        )
        // MARA Stage 6: a photo of the report.
        TestDatabase.update(
            "INSERT INTO photo (id, workspace_id, report_id, author_id, taken_at, size_bytes, sha256) VALUES (gen_random_uuid(), ?, ?, ?, now(), 10, repeat('c', 64))",
            user.workspaceId,
            report,
            user.userId,
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
