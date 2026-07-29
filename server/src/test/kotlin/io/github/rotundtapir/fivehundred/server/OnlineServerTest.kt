// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.net.DisbandReason
import io.github.rotundtapir.cardkit.net.ErrorCode
import io.github.rotundtapir.cardkit.net.ErrorMessage
import io.github.rotundtapir.cardkit.net.GameOver
import io.github.rotundtapir.cardkit.net.Hello
import io.github.rotundtapir.cardkit.net.JoinLobby
import io.github.rotundtapir.cardkit.net.LeaveLobby
import io.github.rotundtapir.cardkit.net.LobbyDisbanded
import io.github.rotundtapir.cardkit.net.Platform
import io.github.rotundtapir.cardkit.net.RequestRematch
import io.github.rotundtapir.cardkit.net.RoomPhase
import io.github.rotundtapir.cardkit.net.SetName
import io.github.rotundtapir.cardkit.net.SetReady
import io.github.rotundtapir.cardkit.net.StartGame
import io.github.rotundtapir.cardkit.net.UpdateRequired
import io.github.rotundtapir.cardkit.net.Welcome
import io.github.rotundtapir.cardkit.server.ServerConfig
import io.github.rotundtapir.cardkit.server.gameServerModule
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.net.AnyViewUpdate
import io.github.rotundtapir.fivehundred.net.CreateLobby
import io.github.rotundtapir.fivehundred.net.LobbyState
import io.github.rotundtapir.fivehundred.net.PROTOCOL_VERSION
import io.github.rotundtapir.fivehundred.net.SubmitAction
import io.github.rotundtapir.fivehundred.net.ViewUpdate
import io.github.rotundtapir.fivehundred.net.forFiveHundred
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class OnlineServerTest {

    private fun ApplicationTestBuilder.startServer(config: ServerConfig): Pair<GameServer, CoroutineScope> {
        // A dedicated thread pool per server, shut down when the scope is cancelled, so a test that
        // ends mid-game leaves no coroutines lingering on the shared Dispatchers.Default — otherwise
        // ~15 sequential testApplication runs accumulate enough pressure to stall later ones.
        val executor = Executors.newFixedThreadPool(4)
        val job = SupervisorJob()
        job.invokeOnCompletion { executor.shutdownNow() }
        val scope = CoroutineScope(job + executor.asCoroutineDispatcher())
        val server = GameServer(config, scope, FiveHundredDescriptor)
        application { gameServerModule(server, config) }
        return server to scope
    }

    // A modest turn timeout: long enough that normal in-process responses never trip it (so games
    // play fast), short enough that a mid-play test tearing down doesn't leave a decide() parked for
    // many seconds. If a slow runner does trip it, playWithBotUntilGameOver tolerates the STALE_ACTION.
    private fun devConfig(overrides: ServerConfig.() -> ServerConfig = { this }): ServerConfig =
        ServerConfig(devMode = true, allowedOrigins = listOf("*"), turnTimeoutMillisOverride = 5000).overrides()

    @Test
    fun `full 2p game with bot fill plays to completion`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                sendMsg(CreateLobby("Alice", playerCount = 2, teamCount = 2, seed = 42))
                val lobby = waitFor<LobbyState>()
                val mySeat = assertNotNull(lobby.yourSeat)
                sendMsg(SetReady(true))
                waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
                sendMsg(StartGame)
                val over = withTimeout(TEST_TIMEOUT_MS) { playWithBotUntilGameOver() }
                assertTrue(over.winnerTeam in 0..1, "winner=${over.winnerTeam}")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `full 4p game with bot fill labels three bots and completes`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.ANDROID))
                waitFor<Welcome>()
                sendMsg(CreateLobby("Alice", playerCount = 4, teamCount = 2, seed = 7))
                val mySeat = assertNotNull(waitFor<LobbyState>().yourSeat)
                sendMsg(SetReady(true))
                waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
                sendMsg(StartGame)
                val playing = waitForLobby { it.phase.name == "PLAYING" }
                assertEquals(3, playing.seats.count { it.isBot }, "three seats should be bots")
                assertTrue(playing.seats.filter { it.isBot }.all { it.name.endsWith("(bot)") })
                // "Alice" is in the bot-name pool; a seated human must keep it off the table.
                assertTrue(playing.seats.none { it.name.equals("Alice (bot)", ignoreCase = true) })
                val over = withTimeout(TEST_TIMEOUT_MS) { playWithBotUntilGameOver() }
                assertTrue(over.winnerTeam in 0..1)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `two clients join by code and play a 4p game together`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            val code = CompletableDeferred<String>()
            coroutineScope {
                val joiner = launch {
                    client.webSocket("/ws") {
                        sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                        waitFor<Welcome>()
                        sendMsg(JoinLobby(code.await(), "Bob"))
                        waitFor<LobbyState>()
                        sendMsg(SetReady(true))
                        withTimeout(TEST_TIMEOUT_MS) { playWithBotUntilGameOver(seed = 2) }
                    }
                }
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.ANDROID))
                    waitFor<Welcome>()
                    sendMsg(CreateLobby("Alice", playerCount = 4, teamCount = 2, seed = 99))
                    code.complete(waitFor<LobbyState>().joinCode)
                    sendMsg(SetReady(true))
                    // Wait until both humans are seated and ready, then start (2 seats become bots).
                    waitForLobby { st ->
                        val humans = st.seats.filter { !it.isBot && it.connected }
                        humans.size == 2 && humans.all { it.ready }
                    }
                    sendMsg(StartGame)
                    val over = withTimeout(TEST_TIMEOUT_MS) { playWithBotUntilGameOver(seed = 1) }
                    assertTrue(over.winnerTeam in 0..1)
                }
                joiner.join()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `client below minimum version is told to update`() = testApplication {
        val (_, scope) = startServer(devConfig { copy(minAppVersion = "0.5.0") })
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                val update = waitFor<UpdateRequired>()
                assertEquals("0.5.0", update.minAppVersion)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a disallowed name is rejected`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                sendMsg(CreateLobby("admin", playerCount = 2, teamCount = 2))
                assertEquals(ErrorCode.BAD_NAME, waitFor<ErrorMessage>().code)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a name already taken in the lobby is rejected case-insensitively`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            val code = CompletableDeferred<String>()
            coroutineScope {
                val joiner = launch {
                    client.webSocket("/ws") {
                        sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                        waitFor<Welcome>()
                        sendMsg(JoinLobby(code.await(), "alice"))
                        assertEquals(ErrorCode.NAME_TAKEN, waitFor<ErrorMessage>().code)
                        sendMsg(JoinLobby(code.await(), "Bob"))
                        waitFor<LobbyState>()
                        // Renaming onto another player's name is rejected too...
                        sendMsg(SetName("ALICE"))
                        assertEquals(ErrorCode.NAME_TAKEN, waitFor<ErrorMessage>().code)
                        // ...but re-casing your own name is not a collision with yourself.
                        sendMsg(SetName("BOB"))
                        waitForLobby { st -> st.seats.any { it.name == "BOB" } }
                    }
                }
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.ANDROID))
                    waitFor<Welcome>()
                    sendMsg(CreateLobby("Alice", playerCount = 4, teamCount = 2))
                    code.complete(waitFor<LobbyState>().joinCode)
                    joiner.join() // keep the creator's socket (and the lobby) alive while the joiner asserts
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a stale action is rejected without disturbing the game`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                sendMsg(CreateLobby("Alice", playerCount = 2, teamCount = 2, seed = 42))
                val mySeat = assertNotNull(waitFor<LobbyState>().yourSeat)
                sendMsg(SetReady(true))
                waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
                sendMsg(StartGame)
                // Submit an action with a bogus (stale) stateVersion; expect a non-fatal rejection.
                waitFor<ViewUpdate>()
                sendMsg(SubmitAction(stateVersion = -1, action = Action.PlaceBid(Bid.Pass)))
                val error = waitFor<ErrorMessage>()
                assertEquals(ErrorCode.STALE_ACTION, error.code)
                assertTrue(!error.fatal)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `draining server refuses new lobbies`() = testApplication {
        val (server, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            server.rooms.setDraining(true)
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                sendMsg(CreateLobby("Alice", playerCount = 2, teamCount = 2))
                assertEquals(ErrorCode.SERVER_DRAINING, waitFor<ErrorMessage>().code)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reconnecting with the session token resumes the room`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            val token = CompletableDeferred<String>()
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                token.complete(waitFor<Welcome>().sessionToken)
                sendMsg(CreateLobby("Alice", playerCount = 2, teamCount = 2, seed = 42))
                val mySeat = assertNotNull(waitFor<LobbyState>().yourSeat)
                sendMsg(SetReady(true))
                waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
                sendMsg(StartGame)
                waitFor<ViewUpdate>() // game underway; now drop the socket
            }
            // Reconnect with the same token: the room still exists, so we resume and get our view.
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB, sessionToken = token.await()))
                val welcome = waitFor<Welcome>()
                assertNotNull(welcome.resumed, "expected to resume into the room")
                withTimeout(TEST_TIMEOUT_MS) { waitFor<ViewUpdate>() }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a duplicate of an accepted action is a silent no-op`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                sendMsg(CreateLobby("Alice", playerCount = 2, teamCount = 2, seed = 42))
                val mySeat = assertNotNull(waitFor<LobbyState>().yourSeat)
                sendMsg(SetReady(true))
                waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
                sendMsg(StartGame)
                // On our first turn, submit the very same action twice. The duplicate must be
                // deduped (no ILLEGAL/STALE error), and the game must still play out.
                val bot = FiveHundredBot()
                val rng = Random(1)
                var duplicated = false
                val over = withTimeout(TEST_TIMEOUT_MS) {
                    var result: GameOver? = null
                    while (result == null) {
                        when (val m = nextMsg()) {
                            is GameOver -> result = m
                            is AnyViewUpdate -> {
                                val update = m.forFiveHundred()
                                if (update.view.isMyTurn) {
                                    val action = bot.decide(update.view, rng)
                                    sendMsg(SubmitAction(update.stateVersion, action))
                                    if (!duplicated) {
                                        sendMsg(SubmitAction(update.stateVersion, action)) // exact duplicate
                                        duplicated = true
                                    }
                                }
                            }
                            is ErrorMessage -> error("duplicate must not be rejected: $m")
                            else -> Unit
                        }
                    }
                    result
                }
                assertTrue(duplicated)
                assertTrue(over.winnerTeam in 0..1)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `rematch reopens the bot seat and a second game plays`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                sendMsg(CreateLobby("Alice", playerCount = 2, teamCount = 2, seed = 42))
                val mySeat = assertNotNull(waitFor<LobbyState>().yourSeat)
                sendMsg(SetReady(true))
                waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
                sendMsg(StartGame)
                withTimeout(TEST_TIMEOUT_MS) { playWithBotUntilGameOver() }
                sendMsg(RequestRematch)
                // Back in the lobby with the previously-bot seat reopened and not ready.
                val lobby = waitForLobby { it.phase == RoomPhase.LOBBY }
                assertTrue(lobby.seats.none { it.isBot }, "bot seats should reopen on rematch")
                assertTrue(lobby.seats.none { it.ready }, "ready flags should reset on rematch")
                sendMsg(SetReady(true))
                waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
                sendMsg(StartGame)
                val over = withTimeout(TEST_TIMEOUT_MS) { playWithBotUntilGameOver(seed = 2) }
                assertTrue(over.winnerTeam in 0..1)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a six-player three-team game plays to completion`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                sendMsg(CreateLobby("Alice", playerCount = 6, teamCount = 3, seed = 11))
                val mySeat = assertNotNull(waitFor<LobbyState>().yourSeat)
                sendMsg(SetReady(true))
                waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
                sendMsg(StartGame)
                val playing = waitForLobby { it.phase == RoomPhase.PLAYING }
                assertEquals(5, playing.seats.count { it.isBot }, "five seats fill with bots")
                val over = withTimeout(TEST_TIMEOUT_MS) { playWithBotUntilGameOver() }
                assertTrue(over.winnerTeam in 0..2, "three teams ⇒ winner in 0..2")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a wrong protocol version is told to update`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(protocolVersion = PROTOCOL_VERSION + 99, appVersion = "9.9.9", platform = Platform.WEB))
                assertNotNull(waitFor<UpdateRequired>())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `messages over the rate limit are rejected`() = testApplication {
        // devMode off so the per-socket limiter runs; wildcard origin so the handshake still opens.
        val (_, scope) = startServer(
            ServerConfig(devMode = false, allowedOrigins = listOf("*"), messageRatePerSecond = 1, messageBurst = 2),
        )
        val client = createClient { install(WebSockets) }
        try {
            client.webSocket("/ws") {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                repeat(6) { sendMsg(SetName("spammer")) } // well over burst=2
                val rateLimited = withTimeout(TEST_TIMEOUT_MS) {
                    var seen = false
                    while (!seen) {
                        val m = nextMsg()
                        if (m is ErrorMessage && m.code == ErrorCode.RATE_LIMITED) seen = true
                    }
                    seen
                }
                assertTrue(rateLimited, "a burst past the limit should yield RATE_LIMITED")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a disallowed origin never gets a welcome`() = testApplication {
        val (_, scope) = startServer(
            ServerConfig(devMode = false, allowedOrigins = listOf("https://good.example")),
        )
        val client = createClient { install(WebSockets) }
        try {
            val welcomed = runCatching {
                client.webSocket("/ws", request = { header(HttpHeaders.Origin, "https://evil.example") }) {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    withTimeout(5_000) { waitFor<Welcome>() }
                }
            }.isSuccess
            assertFalse(welcomed, "a disallowed Origin must be refused before any Welcome")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a cleanly-left player's token no longer resumes the room`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { install(WebSockets) }
        try {
            val code = CompletableDeferred<String>()
            val aliceMayLeave = CompletableDeferred<Unit>()
            coroutineScope {
                // Alice keeps the room alive (in its own connection) until Bob is done.
                launch {
                    client.webSocket("/ws") {
                        sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.ANDROID))
                        waitFor<Welcome>()
                        sendMsg(CreateLobby("Alice", playerCount = 2, teamCount = 2))
                        code.complete(waitFor<LobbyState>().joinCode)
                        aliceMayLeave.await()
                    }
                }
                val bobToken = CompletableDeferred<String>()
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    bobToken.complete(waitFor<Welcome>().sessionToken)
                    sendMsg(JoinLobby(code.await(), "Bob"))
                    waitFor<LobbyState>()
                    sendMsg(LeaveLobby) // clean leave clears Bob's session binding
                }
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB, sessionToken = bobToken.await()))
                    assertEquals(null, waitFor<Welcome>().resumed, "a cleanly-left token must not resume")
                }
                aliceMayLeave.complete(Unit)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a creator socket drop in the lobby is held for the grace window and the token reclaims it`() =
        testApplication {
            // Generous grace so the reconnect below always lands inside it, even on a slow runner.
            val (_, scope) = startServer(devConfig { copy(lobbyDisconnectGraceMillis = 30_000) })
            val client = createClient { install(WebSockets) }
            try {
                val token = CompletableDeferred<String>()
                val code = CompletableDeferred<String>()
                // Alice creates a lobby, then her socket drops with no clean leave — a page reload.
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    token.complete(waitFor<Welcome>().sessionToken)
                    sendMsg(CreateLobby("Alice", playerCount = 2, teamCount = 2))
                    code.complete(waitFor<LobbyState>().joinCode)
                }
                // The reloaded page reconnects with the persisted token: the lobby must still exist
                // (not CREATOR_DISBANDED) and the reclaimed seat must still be the creator's.
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB, sessionToken = token.await()))
                    val resumed = assertNotNull(waitFor<Welcome>().resumed, "the lobby must survive the drop")
                    assertEquals(code.await(), resumed.joinCode)
                    val lobby = waitFor<LobbyState>()
                    assertEquals(lobby.creatorSeat, lobby.yourSeat, "the reclaimed seat is still the creator")
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a creator drop past the grace window disbands the lobby for the guests`() = testApplication {
        val (_, scope) = startServer(devConfig { copy(lobbyDisconnectGraceMillis = 300) })
        val client = createClient { install(WebSockets) }
        try {
            val code = CompletableDeferred<String>()
            val aliceGone = CompletableDeferred<Unit>()
            coroutineScope {
                launch {
                    client.webSocket("/ws") {
                        sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                        waitFor<Welcome>()
                        sendMsg(CreateLobby("Alice", playerCount = 4, teamCount = 2))
                        code.complete(waitFor<LobbyState>().joinCode)
                        // Wait for Bob to be seated (so he observes what follows), then vanish.
                        waitForLobby { st -> st.seats.count { it.name.isNotBlank() } == 2 }
                    }
                    aliceGone.complete(Unit)
                }
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    waitFor<Welcome>()
                    sendMsg(JoinLobby(code.await(), "Bob"))
                    waitFor<LobbyState>()
                    aliceGone.await()
                    // First the roster shows the creator disconnected (the room is NOT disbanded yet)…
                    val held = withTimeout(TEST_TIMEOUT_MS) {
                        waitForLobby { st -> !st.seats.first { it.seat == st.creatorSeat }.connected }
                    }
                    assertEquals(RoomPhase.LOBBY, held.phase)
                    // …then, once the grace runs out with no reconnect, the disband lands.
                    val disbanded = withTimeout(TEST_TIMEOUT_MS) { waitFor<LobbyDisbanded>() }
                    assertEquals(DisbandReason.CREATOR_DISBANDED, disbanded.reason)
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a guest drop past the grace window frees the seat and stops the token resuming`() = testApplication {
        val (_, scope) = startServer(devConfig { copy(lobbyDisconnectGraceMillis = 300) })
        val client = createClient { install(WebSockets) }
        try {
            val code = CompletableDeferred<String>()
            val bobToken = CompletableDeferred<String>()
            coroutineScope {
                val bob = launch {
                    client.webSocket("/ws") {
                        sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                        bobToken.complete(waitFor<Welcome>().sessionToken)
                        sendMsg(JoinLobby(code.await(), "Bob"))
                        waitFor<LobbyState>() // seated; now drop without a clean leave
                    }
                }
                client.webSocket("/ws") {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.ANDROID))
                    waitFor<Welcome>()
                    sendMsg(CreateLobby("Alice", playerCount = 4, teamCount = 2))
                    code.complete(waitFor<LobbyState>().joinCode)
                    bob.join()
                    // The seat is freed (name cleared) once the grace expires — no disband.
                    withTimeout(TEST_TIMEOUT_MS) {
                        waitForLobby { st -> st.seats.count { it.name.isNotBlank() } == 1 }
                    }
                    // And Bob's token no longer resumes into the room he timed out of.
                    launch {
                        client.webSocket("/ws") {
                            sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB, sessionToken = bobToken.await()))
                            assertEquals(null, waitFor<Welcome>().resumed, "an expired seat must not resume")
                        }
                    }.join()
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `health endpoint reports the documented fields`() = testApplication {
        val (_, scope) = startServer(devConfig())
        val client = createClient { }
        try {
            val body = client.get("/health").bodyAsText()
            // The deploy drain loop greps "activeGames" out of this — pin the exact keys.
            assertTrue(body.contains("\"status\":\"ok\""), body)
            assertTrue(body.contains("\"rooms\":"), body)
            assertTrue(body.contains("\"activeGames\":"), body)
            assertTrue(body.contains("\"draining\":"), body)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 60_000L
    }
}
