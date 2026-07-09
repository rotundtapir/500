// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerUnitTest {

    @Test
    fun `version comparison handles differing segment counts`() {
        assertTrue(Versions.isAtLeast("0.3.0", "0.3.0"))
        assertTrue(Versions.isAtLeast("0.3.1", "0.3.0"))
        assertTrue(Versions.isAtLeast("1.0", "0.9.9"))
        assertFalse(Versions.isAtLeast("0.2.9", "0.3.0"))
        assertFalse(Versions.isAtLeast("0.3", "0.3.1"))
    }

    @Test
    fun `token bucket allows a burst then refills over time`() {
        var now = 0L
        val limiter = RateLimiter(ratePerSecond = 10, burst = 3) { now }
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire(), "burst exhausted")
        now += 100 // one token refills at 10/s
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `sliding window enforces a per-key limit`() {
        var now = 0L
        val counter = SlidingWindowCounter(windowMillis = 1000, limit = 2) { now }
        assertTrue(counter.tryRecord("a"))
        assertTrue(counter.tryRecord("a"))
        assertFalse(counter.tryRecord("a"))
        assertTrue(counter.tryRecord("b"), "different key has its own budget")
        now += 1001
        assertTrue(counter.tryRecord("a"), "window slid; budget restored")
    }

    @Test
    fun `metrics render in Prometheus text format`() {
        val metrics = Metrics()
        metrics.connectionOpened()
        metrics.gameStarted()
        val text = metrics.render(roomsActive = 3, draining = true)
        assertTrue(text.contains("fivehundred_connections_total 1"))
        assertTrue(text.contains("fivehundred_rooms_active 3"))
        assertTrue(text.contains("fivehundred_draining 1"))
        assertTrue(text.contains("fivehundred_rejections_total{reason="))
    }

    @Test
    fun `config reads from an injected environment`() {
        val env = mapOf(
            "PORT" to "9000",
            "DEV_MODE" to "true",
            "ALLOWED_ORIGINS" to "https://a.example, https://b.example",
            "MAX_CONNECTIONS_PER_IP" to "3",
        )
        val config = ServerConfig.fromEnv { env[it] }
        assertEquals(9000, config.port)
        assertTrue(config.devMode)
        assertEquals(listOf("https://a.example", "https://b.example"), config.allowedOrigins)
        assertEquals(3, config.maxConnectionsPerIp)
    }

    @Test
    fun `origin allowlist and wildcard`() {
        val restricted = ServerConfig(allowedOrigins = listOf("https://rotundtapir.github.io"))
        assertTrue(restricted.originAllowed("https://rotundtapir.github.io"))
        assertTrue(restricted.originAllowed(null), "non-browser clients send no Origin")
        assertFalse(restricted.originAllowed("https://evil.example"))
        assertTrue(ServerConfig(allowedOrigins = listOf("*")).originAllowed("https://anything.example"))
    }
}
