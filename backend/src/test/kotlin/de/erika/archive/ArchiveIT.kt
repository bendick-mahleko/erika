package de.erika.archive

import de.erika.platform.jobs.JobQueue
import de.erika.platform.jobs.JobWorker
import de.erika.platform.storage.StorageProperties
import de.erika.platform.tenancy.TenantContext
import de.erika.support.TestDatabase
import de.erika.support.WorkerIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRetentionRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.sql.SQLException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 4 exit criteria for the archive (AR-01, AR-02, BR-02, BR-03): the recomputed hash
 * equals the stored one, and overwriting or deleting through the app role fails.
 */
class ArchiveIT : WorkerIntegrationTest() {
    @Autowired lateinit var archive: Archive

    @Autowired lateinit var tenant: TenantContext

    @Autowired lateinit var tx: TransactionTemplate

    @Autowired lateinit var s3: S3Client

    @Autowired lateinit var storageProps: StorageProperties

    @Autowired lateinit var jobs: JobQueue

    @Autowired lateinit var worker: JobWorker

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var meters: MeterRegistry

    private val today = LocalDate.of(2026, 11, 22)

    @Test
    fun `stores once per workspace, under ws-workspace-sha256, with the retention date and an audit entry`() {
        val ws = workspace()
        val bytes = "<Invoice>${UUID.randomUUID()}</Invoice>".toByteArray()

        val first = inWorkspace(ws) { archive.store(ws, bytes, "application/xml", LocalDate.of(2026, 3, 1), today) }
        val second = inWorkspace(ws) { archive.store(ws, bytes, "application/xml", LocalDate.of(2026, 3, 1), today) }

        assertEquals(first, second, "same bytes, same workspace: stored once")
        assertEquals(Archive.sha256Hex(bytes), first.sha256)
        assertEquals("ws/$ws/${first.sha256}", first.storageKey)
        assertEquals(LocalDate.of(2034, 12, 31), first.retentionUntil)
        assertEquals(ObjectLock.COMPLIANCE, first.objectLock)
        assertNotNull(first.versionId, "the originals bucket is versioned")
        assertEquals(1, auditCount(ws, "archive.object.stored"))

        val opened = inWorkspace(ws) { archive.open(ws, first.sha256)!! }
        assertContentEquals(bytes, opened.bytes)
        assertEquals(first.sha256, Archive.sha256Hex(opened.bytes), "recomputed hash equals stored hash")
    }

    @Test
    fun `the same file in two workspaces is two separate originals`() {
        val a = workspace()
        val b = workspace()
        val bytes = "shared ${UUID.randomUUID()}".toByteArray()
        val inA = inWorkspace(a) { archive.store(a, bytes, "application/xml", null, today) }
        val inB = inWorkspace(b) { archive.store(b, bytes, "application/xml", null, today) }
        assertTrue(inA.storageKey != inB.storageKey)
        assertNull(inWorkspace(a) { archive.find(b, inB.sha256) }, "RLS: workspace A can't see B's original")
    }

    @Test
    fun `the object is locked in COMPLIANCE mode until the end of the retention day`() {
        val ws = workspace()
        val stored = inWorkspace(ws) { archive.store(ws, random(), "application/pdf", LocalDate.of(2024, 5, 1), today) }
        assertEquals(LocalDate.of(2034, 12, 31), stored.retentionUntil, "dated before 2025: 10 years")
        val retention =
            s3
                .getObjectRetention(
                    GetObjectRetentionRequest
                        .builder()
                        .bucket(storageProps.bucket)
                        .key(stored.storageKey)
                        .versionId(stored.versionId)
                        .build(),
                ).retention()
        assertEquals("COMPLIANCE", retention.modeAsString())
        assertEquals(LocalDate.of(2035, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC), retention.retainUntilDate())
    }

    @Test
    fun `deleting the locked version with the app key fails, and overwriting the key doesn't change the original`() {
        val ws = workspace()
        val bytes = random()
        val stored = inWorkspace(ws) { archive.store(ws, bytes, "application/xml", null, today) }

        assertFailsWith<S3Exception> {
            s3.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(storageProps.bucket)
                    .key(stored.storageKey)
                    .versionId(stored.versionId)
                    .build(),
            )
        }
        // A plain write to the same key only adds a version; the archive reads its own version.
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(storageProps.bucket)
                .key(stored.storageKey)
                .build(),
            RequestBody.fromString("tampered"),
        )
        assertContentEquals(bytes, inWorkspace(ws) { archive.open(ws, stored.sha256)!!.bytes })
    }

    @Test
    fun `the app role can neither delete rows nor change their identity, and retention only grows`() {
        val ws = workspace()
        val stored = inWorkspace(ws) { archive.store(ws, random(), "application/xml", null, today) }
        TestDatabase.asApp { c ->
            c.autoCommit = false

            fun scoped(sql: String) {
                c.createStatement().execute("SELECT set_config('app.workspace_id', '$ws', true)")
                try {
                    c.createStatement().executeUpdate(sql)
                } finally {
                    c.rollback()
                }
            }
            val where = "WHERE workspace_id = '$ws' AND sha256 = '${stored.sha256}'"
            assertFailsWith<SQLException> { scoped("DELETE FROM stored_object $where") }
            assertFailsWith<SQLException> { scoped("UPDATE stored_object SET storage_key = 'elsewhere' $where") }
            assertFailsWith<SQLException> { scoped("UPDATE stored_object SET sha256 = repeat('0', 64) $where") }
            val shorter = assertFailsWith<SQLException> { scoped("UPDATE stored_object SET retention_until = '2027-01-01' $where") }
            assertTrue(shorter.message!!.contains("may only be extended"), shorter.message)
            scoped("UPDATE stored_object SET legal_hold = true $where") // allowed column
        }
    }

    @Test
    fun `storing again with a later document date extends the retention and the lock`() {
        val ws = workspace()
        val bytes = random()
        inWorkspace(ws) { archive.store(ws, bytes, "application/xml", null, LocalDate.of(2025, 2, 1)) }
        val extended = inWorkspace(ws) { archive.store(ws, bytes, "application/xml", LocalDate.of(2027, 1, 10), today) }
        assertEquals(LocalDate.of(2035, 12, 31), extended.retentionUntil)
        val earlier = inWorkspace(ws) { archive.store(ws, bytes, "application/xml", LocalDate.of(2025, 2, 1), today) }
        assertEquals(LocalDate.of(2035, 12, 31), earlier.retentionUntil, "never shortened")
    }

    @Test
    fun `a damaged original is refused on open, alerted and audited`() {
        val ws = workspace()
        val genuine = inWorkspace(ws) { archive.store(ws, random(), "application/xml", null, today) }
        val forged = forgedRow(ws, pointingAt = genuine)
        val before = meters.counter("erika.archive.integrity.failures").count()

        assertFailsWith<ArchiveIntegrityException> { inWorkspace(ws) { archive.open(ws, forged) } }

        assertEquals(before + 1, meters.counter("erika.archive.integrity.failures").count())
        assertEquals(1, auditCount(ws, "archive.integrity.failed"), "the audit entry survives the failed transaction")
    }

    @Test
    fun `the integrity check job re-hashes originals and fails when one is damaged`() {
        TestDatabase.update("DELETE FROM stored_object WHERE size_bytes = 1") // forged rows of other tests
        val ws = workspace()
        val genuine = inWorkspace(ws) { archive.store(ws, random(), "application/xml", null, today) }

        assertEquals("SUCCEEDED", runCheck(1.0))
        val verifiedAt =
            TestDatabase.asOwner { c ->
                c.prepareStatement("SELECT last_verified_at FROM stored_object WHERE workspace_id = ? AND sha256 = ?").use {
                    it.setObject(1, ws)
                    it.setString(2, genuine.sha256)
                    it.executeQuery().run {
                        next()
                        getTimestamp(1)
                    }
                }
            }
        assertNotNull(verifiedAt, "intact originals get a verification time")

        forgedRow(ws, pointingAt = genuine)
        assertEquals("FAILED", runCheck(1.0))
        TestDatabase.update("DELETE FROM stored_object WHERE size_bytes = 1")
    }

    @Test
    fun `legal hold can be set and lifted, each change is audited`() {
        val ws = workspace()
        val stored = inWorkspace(ws) { archive.store(ws, random(), "application/xml", null, today) }
        inWorkspace(ws) { archive.setLegalHold(ws, stored.sha256, true, null) }
        inWorkspace(ws) { archive.setLegalHold(ws, stored.sha256, true, null) } // no change, no entry
        assertTrue(inWorkspace(ws) { archive.find(ws, stored.sha256)!!.legalHold })
        inWorkspace(ws) { archive.setLegalHold(ws, stored.sha256, false, null) }
        assertEquals(2, auditCount(ws, "archive.legal_hold.changed"))
    }

    /**
     * A row claiming a different SHA-256 for an existing object version: simulates a damaged
     * original. Marked with size 1 so tests can remove such rows again.
     */
    private fun forgedRow(
        ws: UUID,
        pointingAt: StoredObject,
    ): String {
        val claimed = Archive.sha256Hex(random())
        TestDatabase.update(
            """
            INSERT INTO stored_object (workspace_id, sha256, size_bytes, media_type, storage_key, version_id, retention_until, object_lock)
            VALUES (?, ?, 1, 'application/xml', ?, ?, '2034-12-31', 'COMPLIANCE')
            """.trimIndent(),
            ws,
            claimed,
            pointingAt.storageKey,
            pointingAt.versionId,
        )
        return claimed
    }

    private fun runCheck(fraction: Double): String {
        val id =
            tx.execute {
                jobs.enqueue(ArchiveIntegrityCheckJobHandler.TYPE, ArchiveIntegrityCheckJobHandler.Payload(fraction), maxAttempts = 1)
            }!!
        worker.runOnce()
        return jdbc
            .sql("SELECT state FROM job WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()
    }

    private fun <T> inWorkspace(
        ws: UUID,
        block: () -> T,
    ): T =
        tx.execute {
            tenant.enterWorkspace(ws)
            block()
        } as T

    private fun workspace(): UUID =
        UUID.randomUUID().also {
            TestDatabase.update("INSERT INTO workspace (id, name) VALUES (?, ?)", it, "Archive test")
        }

    private fun random(): ByteArray = "<x>${UUID.randomUUID()}</x>".toByteArray()

    private fun auditCount(
        ws: UUID,
        action: String,
    ): Int =
        TestDatabase.asOwner { c ->
            c.prepareStatement("SELECT count(*) FROM audit_log WHERE workspace_id = ? AND action = ?").use {
                it.setObject(1, ws)
                it.setString(2, action)
                it.executeQuery().run {
                    next()
                    getInt(1)
                }
            }
        }
}
