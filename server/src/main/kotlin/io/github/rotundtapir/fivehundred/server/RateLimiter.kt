// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

/**
 * A simple token-bucket rate limiter. One instance per socket (message rate). The [nowMillis] clock
 * is injectable so tests are deterministic without sleeping.
 *
 * Not thread-safe by itself; each is confined to its socket's single receive loop.
 */
class RateLimiter(
    private val ratePerSecond: Int,
    private val burst: Int,
    private val nowMillis: () -> Long,
) {
    private var tokens: Double = burst.toDouble()
    private var last: Long = nowMillis()

    /** Consume one token. Returns true if allowed, false if the bucket is empty (rate exceeded). */
    fun tryAcquire(): Boolean {
        val now = nowMillis()
        val elapsedSeconds = (now - last).coerceAtLeast(0) / 1000.0
        last = now
        tokens = (tokens + elapsedSeconds * ratePerSecond).coerceAtMost(burst.toDouble())
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }
}

/**
 * A sliding-window counter keyed by an arbitrary key (used for per-IP lobby-creation throttling).
 * Thread-safe via coarse synchronization — call volume is tiny.
 */
class SlidingWindowCounter(
    private val windowMillis: Long,
    private val limit: Int,
    private val nowMillis: () -> Long,
) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /** Record a hit for [key]; returns true if it is within the limit, false if the limit is exceeded. */
    @Synchronized
    fun tryRecord(key: String): Boolean {
        val now = nowMillis()
        val cutoff = now - windowMillis
        val deque = hits.getOrPut(key) { ArrayDeque() }
        while (deque.isNotEmpty() && deque.first() < cutoff) deque.removeFirst()
        return if (deque.size < limit) {
            deque.addLast(now)
            true
        } else {
            false
        }
    }
}
