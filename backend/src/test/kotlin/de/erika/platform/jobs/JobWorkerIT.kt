package de.erika.platform.jobs

import de.erika.support.WorkerIntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Import(JobWorkerIT.Handlers::class)
class JobWorkerIT : WorkerIntegrationTest() {
    @Autowired lateinit var queue: JobQueue

    @Autowired lateinit var worker: JobWorker

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var tx: TransactionTemplate

    @Autowired lateinit var scripted: ScriptedHandler

    @BeforeEach
    fun cleanQueue() {
        jdbc.sql("DELETE FROM job").update()
        scripted.invocations.clear()
    }

    @Test
    fun `runs a queued job and marks it SUCCEEDED`() {
        val id = queue.enqueue(ScriptedHandler.TYPE, Scripted("ok"))

        assertEquals(1, worker.runOnce())

        val job = load(id)
        assertEquals("SUCCEEDED", job.state)
        assertEquals(1, job.attempts)
        assertNotNull(job.finishedAt)
        assertEquals(listOf(1), scripted.attemptsOf("ok"))
    }

    @Test
    fun `retries a failing job with backoff, then succeeds`() {
        val id = queue.enqueue(ScriptedHandler.TYPE, Scripted("flaky", failTimes = 1))

        worker.runOnce()
        val afterFailure = load(id)
        assertEquals("QUEUED", afterFailure.state)
        assertEquals(1, afterFailure.attempts)
        assertTrue(afterFailure.lastError!!.contains("scripted failure"))
        assertTrue(afterFailure.runAt.isAfter(OffsetDateTime.now().plusSeconds(5)), "retry must be delayed")

        assertEquals(0, worker.runOnce(), "not due yet")
        makeDue(id)
        worker.runOnce()

        val done = load(id)
        assertEquals("SUCCEEDED", done.state)
        assertEquals(2, done.attempts)
        assertNull(done.lastError)
    }

    @Test
    fun `marks a job FAILED after max attempts`() {
        val id = queue.enqueue(ScriptedHandler.TYPE, Scripted("broken", failTimes = Int.MAX_VALUE), maxAttempts = 2)

        worker.runOnce()
        makeDue(id)
        worker.runOnce()

        val job = load(id)
        assertEquals("FAILED", job.state)
        assertEquals(2, job.attempts)
        assertNotNull(job.finishedAt)
        makeDue(id)
        assertEquals(0, worker.runOnce(), "FAILED jobs are not picked up again")
    }

    @Test
    fun `scopes the handler transaction to the job's workspace`() {
        val workspace = UUID.randomUUID()
        queue.enqueue(ScriptedHandler.TYPE, Scripted("tenant"), workspaceId = workspace)
        queue.enqueue(ScriptedHandler.TYPE, Scripted("system"))

        worker.runOnce()

        assertEquals(
            workspace.toString(),
            scripted.invocations
                .getValue("tenant")
                .single()
                .workspaceSetting,
        )
        assertTrue(
            scripted.invocations
                .getValue("system")
                .single()
                .workspaceSetting
                .isNullOrEmpty(),
        )
    }

    @Test
    fun `enqueue joins the caller's transaction`() {
        var id: UUID? = null
        tx.executeWithoutResult { status ->
            id = queue.enqueue(ScriptedHandler.TYPE, Scripted("rolled-back"))
            status.setRollbackOnly()
        }

        assertEquals(
            0,
            jdbc
                .sql("SELECT count(*) FROM job WHERE id = :id")
                .param("id", id)
                .query(Int::class.java)
                .single(),
        )
    }

    @Test
    fun `reclaims a job whose worker died while holding the lease`() {
        val id = queue.enqueue(ScriptedHandler.TYPE, Scripted("orphan"))
        jdbc
            .sql(
                """
                UPDATE job SET state = 'RUNNING', attempts = 1, locked_by = 'dead-worker',
                               locked_until = now() - interval '1 minute'
                 WHERE id = :id
                """.trimIndent(),
            ).param("id", id)
            .update()

        worker.runOnce()

        val job = load(id)
        assertEquals("SUCCEEDED", job.state)
        assertEquals(2, job.attempts)
    }

    @Test
    fun `rejects unknown job types at enqueue time`() {
        assertFailsWith<IllegalArgumentException> { queue.enqueue("no.such.type", Scripted("x")) }
    }

    @Test
    fun `honours the requested delay`() {
        val id = queue.enqueue(ScriptedHandler.TYPE, Scripted("later"), delay = Duration.ofMinutes(10))

        assertEquals(0, worker.runOnce())
        assertEquals("QUEUED", load(id).state)
    }

    private fun makeDue(id: UUID) {
        jdbc.sql("UPDATE job SET run_at = now() WHERE id = :id").param("id", id).update()
    }

    private fun load(id: UUID): JobRow =
        jdbc
            .sql("SELECT state, attempts, last_error, run_at, finished_at FROM job WHERE id = :id")
            .param("id", id)
            .query { rs, _ ->
                JobRow(
                    state = rs.getString("state"),
                    attempts = rs.getInt("attempts"),
                    lastError = rs.getString("last_error"),
                    runAt = rs.getObject("run_at", OffsetDateTime::class.java),
                    finishedAt = rs.getObject("finished_at", OffsetDateTime::class.java),
                )
            }.single()

    data class JobRow(
        val state: String,
        val attempts: Int,
        val lastError: String?,
        val runAt: OffsetDateTime,
        val finishedAt: OffsetDateTime?,
    )

    data class Scripted(
        val key: String,
        val failTimes: Int = 0,
    )

    data class Invocation(
        val attempt: Int,
        val workspaceSetting: String?,
    )

    /** Test handler whose behaviour is controlled by its payload. */
    class ScriptedHandler(
        private val jdbc: JdbcClient,
    ) : JobHandler<Scripted> {
        val invocations = ConcurrentHashMap<String, MutableList<Invocation>>()

        override val type = TYPE
        override val payloadType = Scripted::class.java

        override fun handle(
            payload: Scripted,
            context: JobContext,
        ) {
            val setting =
                jdbc
                    .sql("SELECT current_setting('app.workspace_id', true)")
                    .query(String::class.java)
                    .optional()
                    .orElse(null)
            invocations.computeIfAbsent(payload.key) { mutableListOf() }.add(Invocation(context.attempt, setting))
            check(context.attempt > payload.failTimes) { "scripted failure on attempt ${context.attempt}" }
        }

        fun attemptsOf(key: String) = invocations.getValue(key).map { it.attempt }

        companion object {
            const val TYPE = "test.scripted"
        }
    }

    @TestConfiguration
    class Handlers {
        @Bean
        fun scriptedHandler(jdbc: JdbcClient) = ScriptedHandler(jdbc)
    }
}
