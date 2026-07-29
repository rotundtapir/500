// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.net.DisbandReason
import io.github.rotundtapir.cardkit.net.Hello
import io.github.rotundtapir.cardkit.net.JoinLobby
import io.github.rotundtapir.cardkit.net.LobbyDisbanded
import io.github.rotundtapir.cardkit.net.Platform
import io.github.rotundtapir.cardkit.net.RoomPhase
import io.github.rotundtapir.cardkit.net.SetReady
import io.github.rotundtapir.cardkit.net.StartGame
import io.github.rotundtapir.cardkit.net.Welcome
import io.github.rotundtapir.cardkit.server.ServerConfig
import io.github.rotundtapir.cardkit.server.gameServerModule
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.net.CreateLobby
import io.github.rotundtapir.fivehundred.net.LobbyConfig
import io.github.rotundtapir.fivehundred.net.LobbyState
import io.github.rotundtapir.fivehundred.net.PROTOCOL_VERSION
import io.github.rotundtapir.fivehundred.net.SubmitAction
import io.github.rotundtapir.fivehundred.net.ViewUpdate
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Issue #16: a server restart must not lose in-flight rooms. Each test runs a real server against a
 * snapshot directory, kills it (scope cancel — no graceful disband, like a crash or deploy), boots a
 * second server on the same directory, and reconnects with the session tokens from the first life.
 */
class RestartRestoreTest {

    private fun ApplicationTestBuilder.startServer(config: ServerConfig): Pair<GameServer, CoroutineScope> {
        // Mirrors OnlineServerTest: a dedicated pool per server so an ended test leaves nothing
        // parked on the shared dispatcher.
        val executor = Executors.newFixedThreadPool(4)
        val job = SupervisorJob()
        job.invokeOnCompletion { executor.shutdownNow() }
        val scope = CoroutineScope(job + executor.asCoroutineDispatcher())
        val server = GameServer(config, scope, FiveHundredDescriptor)
        server.restoreRooms()
        application { gameServerModule(server, config) }
        return server to scope
    }

    private fun persistentConfig(dataDir: Path, overrides: ServerConfig.() -> ServerConfig = { this }): ServerConfig =
        ServerConfig(
            devMode = true,
            allowedOrigins = listOf("*"),
            turnTimeoutMillisOverride = 5000,
            dataDir = dataDir.toString(),
        ).overrides()

    /** Block until the room's on-disk snapshot satisfies [predicate] (the writer is asynchronous). */
    private fun awaitSnapshot(dataDir: Path, predicate: (RoomSnapshot) -> Boolean): RoomSnapshot {
        val json = Json { ignoreUnknownKeys = true }
        val deadline = System.currentTimeMillis() + SNAPSHOT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val snapshot = dataDir.listDirectoryEntries("*.json").firstNotNullOfOrNull { file ->
                runCatching { json.decodeFromString<RoomSnapshot>(file.readText()) }.getOrNull()
            }
            if (snapshot != null && predicate(snapshot)) return snapshot
            Thread.sleep(SNAPSHOT_POLL_MS)
        }
        error("snapshot never reached the expected state under $dataDir")
    }

    @Test
    fun `mid-game state survives a restart and the reclaimed seat finishes the game`() {
        val dataDir = createTempDirectory("snapshots")
        var token = ""
        var joinCode = ""
        var lastVersionBeforeCrash = 0

        testApplication {
            val (_, scope) = startServer(persistentConfig(dataDir))
            val client = createClient { install(WebSockets) }
            try {
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    token = waitFor<Welcome>().sessionToken
                    sendMsg(CreateLobby("Resa", playerCount = 2, teamCount = 2, seed = 42))
                    joinCode = waitFor<LobbyState>().joinCode
                    sendMsg(SetReady(true))
                    sendMsg(StartGame)
                    // Play a couple of turns so the persisted state is genuinely mid-game.
                    val bot = FiveHundredBot()
                    val rng = Random(9)
                    var moves = 0
                    while (moves < MOVES_BEFORE_CRASH) {
                        val update = waitFor<ViewUpdate>()
                        lastVersionBeforeCrash = update.stateVersion
                        if (update.view.isMyTurn) {
                            sendMsg(SubmitAction(update.stateVersion, bot.decide(update.view, rng)))
                            moves++
                        }
                    }
                    awaitSnapshot(dataDir) { it.phase == RoomPhase.PLAYING && it.gameState != null }
                    // The "crash" happens while this socket is still open: no drain, no disband. If
                    // the client disconnected first, live bot substitution would finish the 2p game
                    // (and delete the snapshot) before the cancel lands.
                    scope.cancel()
                }
            } finally {
                scope.cancel()
            }
        }

        testApplication {
            val (server, scope) = startServer(persistentConfig(dataDir))
            assertEquals(1, server.rooms.roomCount(), "the room should be restored at boot")
            val client = createClient { install(WebSockets) }
            try {
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB, sessionToken = token))
                    val welcome = waitFor<Welcome>()
                    assertEquals(token, welcome.sessionToken, "the old token must still be honoured")
                    assertEquals(joinCode, welcome.resumed?.joinCode)
                    assertEquals(RoomPhase.PLAYING, welcome.resumed?.phase)
                    // No assertion on stateVersion ordering: a hard crash may lose the newest
                    // conflated write, restoring a state (and version) a move or two older. The
                    // client adopts whatever version the server sends, so only the view matters.
                    val update = waitFor<ViewUpdate>()
                    assertTrue(update.stateVersion >= 1 && lastVersionBeforeCrash >= 1)
                    val over = withTimeout(TEST_TIMEOUT_MS) { playWithBotUntilGameOver() }
                    assertTrue(over.winnerTeam in 0..1)
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `restored lobby reserves owned seats and lets the creator reclaim theirs`() {
        val dataDir = createTempDirectory("snapshots")
        var creatorToken = ""
        var creatorSeat = -1

        testApplication {
            val (_, scope) = startServer(persistentConfig(dataDir))
            val client = createClient { install(WebSockets) }
            try {
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    creatorToken = waitFor<Welcome>().sessionToken
                    sendMsg(CreateLobby("Resa", playerCount = 4, teamCount = 2))
                    creatorSeat = assertNotNull(waitFor<LobbyState>().yourSeat).index
                }
                awaitSnapshot(dataDir) { it.phase == RoomPhase.LOBBY && it.seats.any { s -> s.ownerToken != null } }
            } finally {
                scope.cancel()
            }
        }

        testApplication {
            val (server, scope) = startServer(persistentConfig(dataDir))
            assertEquals(1, server.rooms.roomCount())
            val code = server.rooms.all().first().joinCode
            val client = createClient { install(WebSockets) }
            try {
                // A stranger joining the restored lobby must not be given the creator's held seat.
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    waitFor<Welcome>()
                    sendMsg(JoinLobby(code, "Gus"))
                    val lobby = waitFor<LobbyState>()
                    val gusSeat = assertNotNull(lobby.yourSeat)
                    assertTrue(gusSeat.index != creatorSeat, "the creator's restored seat must stay reserved")
                    assertEquals("Resa", lobby.seats.first { it.seat.index == creatorSeat }.name)
                }
                // The creator reconnects with the pre-restart token and lands back in their seat.
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB, sessionToken = creatorToken))
                    val welcome = waitFor<Welcome>()
                    assertEquals(code, welcome.resumed?.joinCode)
                    val lobby = waitFor<LobbyState>()
                    assertEquals(creatorSeat, lobby.yourSeat?.index)
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `restored lobby disbands after the grace if the creator never returns`() {
        val dataDir = createTempDirectory("snapshots")

        testApplication {
            val (_, scope) = startServer(persistentConfig(dataDir))
            val client = createClient { install(WebSockets) }
            try {
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    waitFor<Welcome>()
                    sendMsg(CreateLobby("Resa", playerCount = 2, teamCount = 2))
                    waitFor<LobbyState>()
                }
                awaitSnapshot(dataDir) { it.phase == RoomPhase.LOBBY && it.seats.any { s -> s.ownerToken != null } }
            } finally {
                scope.cancel()
            }
        }

        testApplication {
            val (server, scope) = startServer(
                persistentConfig(dataDir) { copy(lobbyDisconnectGraceMillis = SHORT_GRACE_MS) },
            )
            assertEquals(1, server.rooms.roomCount())
            val code = server.rooms.all().first().joinCode
            val client = createClient { install(WebSockets) }
            try {
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    waitFor<Welcome>()
                    sendMsg(JoinLobby(code, "Gus"))
                    waitFor<LobbyState>()
                    // The creator's seat release doubles as the live grace expiry: no creator ⇒ disband.
                    val disbanded = withTimeout(TEST_TIMEOUT_MS) { waitFor<LobbyDisbanded>() }
                    assertEquals(DisbandReason.CREATOR_DISBANDED, disbanded.reason)
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `expired and finished snapshots are dropped at boot`() {
        val dataDir = createTempDirectory("snapshots")
        val scope = CoroutineScope(SupervisorJob())
        try {
            val persistence = fileRoomPersistence(dataDir, scope)
            val base = snapshot(gameId = "old", joinCode = "AAAA", savedAtMillis = 0) // ancient
            persistence.save(base)
            persistence.save(
                snapshot(gameId = "done", joinCode = "BBBB", savedAtMillis = System.currentTimeMillis())
                    .copy(phase = RoomPhase.FINISHED),
            )
            awaitFileCount(dataDir, 2)

            val server = GameServer(
                ServerConfig(devMode = true, dataDir = dataDir.toString()),
                scope,
                FiveHundredDescriptor,
            )
            server.restoreRooms()
            assertEquals(0, server.rooms.roomCount(), "expired + finished snapshots must not restore")
            awaitFileCount(dataDir, 0) // and their files are cleaned up
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a snapshot from a different schema version is dropped at boot`() {
        val dataDir = createTempDirectory("snapshots")
        val scope = CoroutineScope(SupervisorJob())
        try {
            val persistence = fileRoomPersistence(dataDir, scope)
            persistence.save(
                snapshot(gameId = "future", joinCode = "DDDD", savedAtMillis = System.currentTimeMillis())
                    .copy(snapshotVersion = SNAPSHOT_VERSION + 1),
            )
            awaitFileCount(dataDir, 1)

            val server = GameServer(
                ServerConfig(devMode = true, dataDir = dataDir.toString()),
                scope,
                FiveHundredDescriptor,
            )
            server.restoreRooms()
            assertEquals(0, server.rooms.roomCount(), "a version-mismatched snapshot must not restore")
            awaitFileCount(dataDir, 0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `snapshots round-trip through the file store`() {
        val dataDir = createTempDirectory("snapshots")
        val scope = CoroutineScope(SupervisorJob())
        try {
            val persistence = fileRoomPersistence(dataDir, scope)
            val saved = snapshot(gameId = "g1", joinCode = "CCCC", savedAtMillis = 123)
            persistence.save(saved)
            awaitFileCount(dataDir, 1)
            assertEquals(listOf(saved), persistence.loadAll())
            // The schema version must be present in the file itself, not just filled by a decoder
            // default — it is what lets a future server refuse this snapshot deliberately.
            assertTrue(
                dataDir.listDirectoryEntries("*.json").first().readText().contains("snapshotVersion"),
            )

            persistence.delete("g1")
            awaitFileCount(dataDir, 0)
            assertTrue(persistence.loadAll().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an unreadable snapshot is quarantined, not fatal`() {
        val dataDir = createTempDirectory("snapshots")
        val scope = CoroutineScope(SupervisorJob())
        try {
            dataDir.resolve("broken.json").toFile().writeText("{ not json")
            val persistence = fileRoomPersistence(dataDir, scope)
            assertTrue(persistence.loadAll().isEmpty())
            assertNull(
                dataDir.listDirectoryEntries("*.json").firstOrNull(),
                "the corrupt file should have been moved aside",
            )
        } finally {
            scope.cancel()
        }
    }

    private fun snapshot(gameId: String, joinCode: String, savedAtMillis: Long) = RoomSnapshot(
        snapshotVersion = SNAPSHOT_VERSION,
        gameId = gameId,
        joinCode = joinCode,
        creatorToken = "tok-$gameId",
        lobbyConfig = LobbyConfig(playerCount = 2, teamCount = 2),
        phase = RoomPhase.LOBBY,
        seats = listOf(
            SeatSnapshot("Resa", isBot = false, ownerToken = "tok-$gameId"),
            SeatSnapshot(null, isBot = false, ownerToken = null),
        ),
        stateVersion = 0,
        gameState = null,
        savedAtMillis = savedAtMillis,
    )

    private fun awaitFileCount(dataDir: Path, expected: Int) {
        val deadline = System.currentTimeMillis() + SNAPSHOT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (dataDir.listDirectoryEntries("*.json").size == expected) return
            Thread.sleep(SNAPSHOT_POLL_MS)
        }
        error("expected $expected snapshot files, saw ${dataDir.listDirectoryEntries("*.json")}")
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 60_000L
        const val SNAPSHOT_WAIT_MS = 10_000L
        const val SNAPSHOT_POLL_MS = 25L
        const val SHORT_GRACE_MS = 300L
        const val MOVES_BEFORE_CRASH = 2
    }
}
