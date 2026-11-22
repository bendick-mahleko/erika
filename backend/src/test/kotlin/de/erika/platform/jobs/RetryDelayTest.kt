package de.erika.platform.jobs

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class RetryDelayTest {
    @Test
    fun `backoff doubles from 10 seconds`() {
        assertEquals(Duration.ofSeconds(10), JobWorker.retryDelay(1))
        assertEquals(Duration.ofSeconds(20), JobWorker.retryDelay(2))
        assertEquals(Duration.ofSeconds(40), JobWorker.retryDelay(3))
        assertEquals(Duration.ofSeconds(80), JobWorker.retryDelay(4))
    }

    @Test
    fun `backoff is capped at one hour`() {
        assertEquals(Duration.ofHours(1), JobWorker.retryDelay(10))
        assertEquals(Duration.ofHours(1), JobWorker.retryDelay(1000))
    }

    @Test
    fun `attempt below one is treated as the first attempt`() {
        assertEquals(Duration.ofSeconds(10), JobWorker.retryDelay(0))
    }
}
