package de.erika.audit

import de.erika.platform.audit.AuditableEvent
import de.erika.platform.jobs.JobQueue
import de.erika.platform.jobs.JobWorker
import de.erika.platform.storage.ObjectStorage
import de.erika.support.TestDatabase
import de.erika.support.TestInfrastructure
import de.erika.support.WorkerIntegrationTest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stage 3 exit criterion: changing an audit row by hand makes the chain check fail and
 * raise an alert (NF-10). Runs in the worker role, where the daily check runs.
 */
class AuditChainIT : WorkerIntegrationTest() {
    @Autowired lateinit var events: ApplicationEventPublisher

    @Autowired lateinit var tx: TransactionTemplate

    @Autowired internal lateinit var verifier: AuditChainVerifier

    @Autowired lateinit var jobs: JobQueue

    @Autowired lateinit var worker: JobWorker

    @Autowired lateinit var storage: ObjectStorage

    @Autowired lateinit var meters: MeterRegistry

    @Autowired lateinit var jdbc: JdbcClient

    private data class TestEvent(
        override val workspaceId: UUID,
        override val actorId: UUID?,
        override val action: String,
        override val details: Map<String, String>,
    ) : AuditableEvent

    @BeforeEach
    fun cleanSlate() {
        // Earlier tests in this JVM may have left broken chains on purpose; start from clean workspaces.
        TestDatabase.update("DELETE FROM audit_log")
        // Exports from earlier tests refer to rows deleted above; replace them with empty ones.
        storage.list(TestInfrastructure.BACKUP_BUCKET, "audit-heads/").forEach { key ->
            storage.put(TestInfrastructure.BACKUP_BUCKET, key, """{"heads":[]}""".toByteArray(), "application/json")
        }
    }

    @Test
    fun `entries form a valid chain per workspace and the head is exported`() {
        val a = workspace()
        val b = workspace()
        record(a, 3)
        record(b, 2)

        val result = verifier.verifyAll()
        assertTrue(result.ok, result.problems.toString())
        assertEquals(mapOf(a to 3L, b to 2L), result.heads.filter { it.workspaceId in setOf(a, b) }.associate { it.workspaceId to it.seq })

        verifier.exportHeads(result.heads)
        val key = storage.list(TestInfrastructure.BACKUP_BUCKET, "audit-heads/").last()
        val exported = String(storage.get(TestInfrastructure.BACKUP_BUCKET, key)!!)
        assertTrue(exported.contains(a.toString()) && exported.contains(result.heads.first { it.workspaceId == a }.hash))
    }

    @Test
    fun `a row changed by hand breaks the chain, fails the job and raises the alert metric`() {
        val ws = workspace()
        record(ws, 4)
        assertTrue(verifier.verifyAll().ok)

        // Tampering needs the schema owner: the app role has no UPDATE on audit_log.
        TestDatabase.update("UPDATE audit_log SET payload = '{\"n\":\"99\"}' WHERE workspace_id = ? AND seq = 2", ws)

        val result = verifier.verifyAll()
        assertEquals(listOf(ChainProblem(ws, 2, "content hash mismatch")), result.problems.filter { it.workspaceId == ws })
        assertEquals(1.0, meters.get("erika.audit.chain.broken").gauge().value())

        val jobId = tx.execute { jobs.enqueue(AuditChainCheckJobHandler.TYPE, AuditChainCheckJobHandler.Payload(), maxAttempts = 1) }!!
        worker.runOnce()
        val (state, error) =
            jdbc
                .sql("SELECT state, last_error FROM job WHERE id = :id")
                .param("id", jobId)
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .single()
        assertEquals("FAILED", state)
        assertTrue(error.contains("AuditChainBrokenException"))
    }

    @Test
    fun `a removed entry in the middle is detected`() {
        val ws = workspace()
        record(ws, 3)
        TestDatabase.update("DELETE FROM audit_log WHERE workspace_id = ? AND seq = 2", ws)
        assertEquals(
            "sequence gap",
            verifier
                .verifyAll()
                .problems
                .single { it.workspaceId == ws }
                .reason,
        )
    }

    @Test
    fun `removing the newest entries is detected against the exported head`() {
        val ws = workspace()
        record(ws, 3)
        val first = verifier.verifyAll()
        verifier.exportHeads(first.heads)

        // The shortened chain is internally consistent; only the exported head reveals it.
        TestDatabase.update("DELETE FROM audit_log WHERE workspace_id = ? AND seq = 3", ws)
        val problem = verifier.verifyAll().problems.single { it.workspaceId == ws }
        assertEquals(3, problem.seq)
        assertEquals("exported head no longer matches", problem.reason)
    }

    @Test
    fun `the daily job exports heads when all chains are valid`() {
        val ws = workspace()
        record(ws, 1)
        val jobId = tx.execute { jobs.enqueue(AuditChainCheckJobHandler.TYPE, AuditChainCheckJobHandler.Payload(), maxAttempts = 1) }!!
        worker.runOnce()
        assertEquals(
            "SUCCEEDED",
            jdbc
                .sql("SELECT state FROM job WHERE id = :id")
                .param("id", jobId)
                .query(String::class.java)
                .single(),
        )
        val key = storage.list(TestInfrastructure.BACKUP_BUCKET, "audit-heads/").last()
        assertTrue(String(storage.get(TestInfrastructure.BACKUP_BUCKET, key)!!).contains(ws.toString()))
    }

    private fun workspace(): UUID {
        val id = UUID.randomUUID()
        TestDatabase.update("INSERT INTO workspace (id, name) VALUES (?, ?)", id, "Audit test $id")
        return id
    }

    private fun record(
        ws: UUID,
        count: Int,
    ) {
        repeat(count) { n ->
            tx.executeWithoutResult { events.publishEvent(TestEvent(ws, null, "test.event", mapOf("n" to n.toString()))) }
        }
    }
}
