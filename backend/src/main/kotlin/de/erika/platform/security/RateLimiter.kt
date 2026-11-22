package de.erika.platform.security

import java.time.Clock
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding-window limiter in memory: at most [limit] events per key within [window].
 * Per API instance; with several instances the effective limit multiplies, which is
 * acceptable as a first line of defence (the account lockout is in the database).
 */
class RateLimiter(
    private val limit: Int,
    private val window: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val events = ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** Records an attempt; returns false if the key is over its limit. */
    fun tryAcquire(key: String): Boolean {
        val now = clock.millis()
        val queue = events.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.peekFirst() <= now - window.toMillis()) queue.pollFirst()
            if (queue.size >= limit) return false
            queue.addLast(now)
        }
        if (events.size > MAX_KEYS) evictIdle(now)
        return true
    }

    private fun evictIdle(now: Long) {
        events.entries.removeIf { (_, q) -> synchronized(q) { q.isEmpty() || q.peekLast() <= now - window.toMillis() } }
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}
