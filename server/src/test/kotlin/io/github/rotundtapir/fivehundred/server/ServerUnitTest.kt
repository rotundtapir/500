// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.server.AbuseLog
import io.github.rotundtapir.cardkit.server.Metrics
import io.github.rotundtapir.cardkit.server.RateLimiter
import io.github.rotundtapir.cardkit.server.ServerConfig
import io.github.rotundtapir.cardkit.server.SessionRegistry
import io.github.rotundtapir.cardkit.server.SlidingWindowCounter
import io.github.rotundtapir.cardkit.server.Versions
import io.github.rotundtapir.fivehundred.net.LobbyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    fun `sliding window evicts keys once their hits age out`() {
        var now = 0L
        val counter = SlidingWindowCounter(windowMillis = 1000, limit = 5) { now }
        counter.tryRecord("a")
        counter.tryRecord("b")
        assertEquals(2, counter.keyCount())
        now += 2000 // both keys' hits are now stale
        counter.evictStale()
        assertEquals(0, counter.keyCount(), "stale keys should be dropped by the sweep")
    }

    @Test
    fun `session registry binds, looks up, clears, and evicts by TTL`() {
        var now = 0L
        val registry = SessionRegistry(nowMillis = { now })
        val token = registry.newToken()
        registry.bind(token, "game-1", Seat(2))
        assertEquals("game-1", registry.lookup(token)?.gameId)
        assertEquals(Seat(2), registry.lookup(token)?.seat)

        registry.clear(token)
        assertNull(registry.lookup(token), "a cleared token is forgotten")

        val stale = registry.newToken()
        val fresh = registry.newToken()
        now += 1000
        registry.lookup(fresh) // refresh fresh's TTL
        now += 500
        registry.evictStale(ttlMillis = 1000)
        assertNull(registry.lookup(stale), "the untouched token is swept")
        assertEquals(1, registry.size(), "the recently-touched token survives")
    }

    @Test
    fun `per-IP connection cap admits up to the limit, refuses beyond, and frees on close`() {
        val server = GameServer(
            ServerConfig(devMode = false, maxConnectionsPerIp = 2),
            CoroutineScope(SupervisorJob()),
            FiveHundredDescriptor,
        )
        assertTrue(server.tryOpenConnection("1.2.3.4"))
        assertTrue(server.tryOpenConnection("1.2.3.4"))
        assertFalse(server.tryOpenConnection("1.2.3.4"), "third over the cap of 2 is refused")
        assertEquals(2, server.connectionsFor("1.2.3.4"), "a refused attempt is rolled back")
        server.closeConnection("1.2.3.4")
        assertTrue(server.tryOpenConnection("1.2.3.4"), "a freed slot admits a new connection")
        assertTrue(server.tryOpenConnection("5.6.7.8"), "a different IP has its own budget")
    }

    @Test
    fun `dev mode bypasses the per-IP connection cap`() {
        val server = GameServer(
            ServerConfig(devMode = true, maxConnectionsPerIp = 1),
            CoroutineScope(SupervisorJob()),
            FiveHundredDescriptor,
        )
        repeat(10) { assertTrue(server.tryOpenConnection("1.2.3.4")) }
    }

    @Test
    fun `join codes use only unambiguous characters and are unique`() {
        // Each created room launches actor + idle-ticker coroutines on this scope; cancel it at the
        // end so they don't leak onto Dispatchers.Default and starve other tests in the same JVM.
        val scope = CoroutineScope(SupervisorJob())
        try {
            val registry = RoomRegistry(
                ServerConfig(devMode = true),
                scope,
                FiveHundredDescriptor,
                SessionRegistry(),
                Metrics(FiveHundredDescriptor.metricsPrefix),
                AbuseLog(),
            )
            val codes = (1..200).mapNotNull {
                (registry.create("tok-$it", LobbyConfig(playerCount = 2, teamCount = 2), null)
                    as? RoomCreated)?.room?.joinCode
            }
            assertEquals(200, codes.size)
            assertEquals(codes.size, codes.toSet().size, "codes must be unique")
            val forbidden = Regex("[^23456789ABCDEFGHJKLMNPQRSTUVWXYZ]")
            codes.forEach { code ->
                assertEquals(4, code.length, "codes are 4 chars: $code")
                assertFalse(forbidden.containsMatchIn(code), "no uppercase-ambiguous glyphs (0/O/1/I) in $code")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `metrics render in Prometheus text format`() {
        val metrics = Metrics(FiveHundredDescriptor.metricsPrefix)
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
            "LOBBY_GRACE_MILLIS" to "5000",
        )
        val config = ServerConfig.fromEnv { env[it] }
        assertEquals(9000, config.port)
        assertTrue(config.devMode)
        assertEquals(listOf("https://a.example", "https://b.example"), config.allowedOrigins)
        assertEquals(3, config.maxConnectionsPerIp)
        assertEquals(5000L, config.lobbyDisconnectGraceMillis)
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
