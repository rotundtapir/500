// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.net.CreateLobby
import io.github.rotundtapir.fivehundred.net.ErrorCode
import io.github.rotundtapir.fivehundred.net.ErrorMessage
import io.github.rotundtapir.fivehundred.net.Hello
import io.github.rotundtapir.fivehundred.net.JoinLobby
import io.github.rotundtapir.fivehundred.net.LobbyState
import io.github.rotundtapir.fivehundred.net.PROTOCOL_VERSION
import io.github.rotundtapir.fivehundred.net.Platform
import io.github.rotundtapir.fivehundred.net.SetReady
import io.github.rotundtapir.fivehundred.net.StartGame
import io.github.rotundtapir.fivehundred.net.SubmitAction
import io.github.rotundtapir.fivehundred.net.UpdateRequired
import io.github.rotundtapir.fivehundred.net.ViewUpdate
import io.github.rotundtapir.fivehundred.net.Welcome
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OnlineServerTest {

    private fun ApplicationTestBuilder.startServer(config: ServerConfig): Pair<GameServer, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = GameServer(config, scope)
        application { gameServerModule(server, config) }
        return server to scope
    }

    private fun devConfig(overrides: ServerConfig.() -> ServerConfig = { this }): ServerConfig =
        ServerConfig(devMode = true, allowedOrigins = listOf("*"), turnTimeoutMillisOverride = 3000).overrides()

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

    private companion object {
        const val TEST_TIMEOUT_MS = 60_000L
    }
}
