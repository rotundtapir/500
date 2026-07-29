// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.net.RoomPhase
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.net.LobbyConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Pins the on-disk snapshot format, which is a compatibility contract of its own: a server that
 * restarts must be able to read the files its predecessor wrote, or the games those files represent
 * are lost. [SNAPSHOT_VERSION] is the escape hatch for a deliberate break — this test is
 * here so an *accidental* one fails the build instead.
 *
 * The engine state is deliberately not pinned byte for byte: it is large, and it is allowed to change
 * (behind a version bump). What is pinned is the envelope around it — field names and order — plus
 * the round trip and the real load path.
 */
class SnapshotFormatTest {

    private val json = Json { ignoreUnknownKeys = true } // the config FileRoomPersistence uses

    private fun snapshot() = RoomSnapshot(
        snapshotVersion = SNAPSHOT_VERSION,
        gameId = "game-1",
        joinCode = "AB12",
        creatorToken = "tok",
        lobbyConfig = LobbyConfig(playerCount = 2, teamCount = 2),
        phase = RoomPhase.PLAYING,
        seats = listOf(
            SeatSnapshot("Alice", isBot = false, ownerToken = "tok"),
            SeatSnapshot("Ivy (bot)", isBot = true, ownerToken = null),
        ),
        stateVersion = 4,
        gameState = FiveHundredRules(playerCount = 2, teamCount = 2).newGame(7L),
        savedAtMillis = 1234,
    )

    @Test
    fun `the snapshot envelope keeps its field names and order`() {
        val encoded = json.encodeToString(snapshotSerializer, snapshot())
        assertTrue(
            encoded.startsWith(
                """{"snapshotVersion":1,"gameId":"game-1","joinCode":"AB12","creatorToken":"tok",""" +
                    """"lobbyConfig":{"playerCount":2,"teamCount":2},"phase":"playing",""" +
                    """"seats":[{"name":"Alice","isBot":false,"ownerToken":"tok"},""" +
                    """{"name":"Ivy (bot)","isBot":true,"ownerToken":null}],"stateVersion":4,""" +
                    """"gameState":{""",
            ),
            "snapshot envelope changed shape: $encoded",
        )
        // The engine state is inlined directly, with no wrapper object around it, and the tail
        // field follows it.
        assertTrue(encoded.endsWith(""","savedAtMillis":1234}"""), "snapshot tail changed shape: $encoded")
    }

    @Test
    fun `a snapshot round-trips with its engine state intact`() {
        val original = snapshot()
        val decoded = json.decodeFromString(
            snapshotSerializer,
            json.encodeToString(snapshotSerializer, original),
        )
        assertEquals(original, decoded)
        assertEquals(original.gameState, decoded.gameState, "the authoritative state must survive verbatim")
    }

    @Test
    fun `a file written in this format is picked up by the real load path`() = runBlocking {
        val dir = Files.createTempDirectory("500-snapshot-format")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val original = snapshot()
            Files.writeString(
                dir.resolve("${original.gameId}.json"),
                json.encodeToString(snapshotSerializer, original),
            )
            val loaded = fileRoomPersistence(dir, scope).loadAll()
            assertEquals(listOf(original), loaded)
            assertTrue(
                Files.list(dir).use { paths -> paths.noneMatch { it.toString().endsWith(".corrupt") } },
                "a well-formed snapshot must not be quarantined",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a snapshot written by this build survives the write-and-load round trip on disk`() = runBlocking {
        val dir = Files.createTempDirectory("500-snapshot-writeback")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val persistence = fileRoomPersistence(dir, scope)
            val original = snapshot()
            persistence.save(original)
            persistence.flushSync()
            val loaded = withTimeoutOrNull(AWAIT_MILLIS) {
                var found = persistence.loadAll()
                while (found.isEmpty()) {
                    delay(POLL_MILLIS)
                    found = persistence.loadAll()
                }
                found
            }
            assertEquals(listOf(original), loaded)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val AWAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 20L
    }
}
