// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.net.ClientMessage
import io.github.rotundtapir.cardkit.net.ErrorCode
import io.github.rotundtapir.cardkit.net.ErrorMessage
import io.github.rotundtapir.cardkit.net.GameOver
import io.github.rotundtapir.cardkit.net.ServerMessage
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.net.AnyViewUpdate
import io.github.rotundtapir.fivehundred.net.LobbyState
import io.github.rotundtapir.fivehundred.net.SubmitAction
import io.github.rotundtapir.fivehundred.net.ViewUpdate
import io.github.rotundtapir.fivehundred.net.WireJson
import io.github.rotundtapir.fivehundred.net.forFiveHundred
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.random.Random

/** Encode and send a client message over the test WebSocket. */
suspend fun DefaultClientWebSocketSession.sendMsg(message: ClientMessage) {
    send(Frame.Text(WireJson.encodeToString<ClientMessage>(message)))
}

/** Receive and decode the next server message, skipping any non-text frames (pings). */
suspend fun DefaultClientWebSocketSession.nextMsg(): ServerMessage {
    while (true) {
        val frame = incoming.receive()
        if (frame is Frame.Text) return WireJson.decodeFromString<ServerMessage>(frame.readText())
    }
}

/**
 * Receive until a message of type [T] arrives, returning it (discarding earlier messages). Callers
 * that need a bound wrap this in `withTimeout` themselves — a `withTimeout` here fires immediately
 * under `testApplication`'s scheduler, which idle-advances virtual time while we wait on a real socket.
 */
suspend inline fun <reified T : ServerMessage> DefaultClientWebSocketSession.waitFor(): T {
    while (true) {
        val message = nextMsg()
        if (message is T) return message
    }
}

/** Wait for a [LobbyState] satisfying [predicate]. */
suspend fun DefaultClientWebSocketSession.waitForLobby(predicate: (LobbyState) -> Boolean): LobbyState {
    while (true) {
        val lobby = waitFor<LobbyState>()
        if (predicate(lobby)) return lobby
    }
}

/**
 * Play this seat with [FiveHundredBot] until [GameOver], returning it. Any [ViewUpdate] where it is
 * our turn is answered with a bot move echoing the update's [ViewUpdate.stateVersion].
 */
suspend fun DefaultClientWebSocketSession.playWithBotUntilGameOver(seed: Long = 1L): GameOver {
    val bot = FiveHundredBot()
    val rng = Random(seed)
    while (true) {
        when (val message = nextMsg()) {
            is GameOver -> return message
            is AnyViewUpdate -> {
                val update = message.forFiveHundred()
                if (update.view.isMyTurn) {
                    val action = bot.decide(update.view, rng)
                    sendMsg(SubmitAction(update.stateVersion, action))
                }
            }
            // A STALE_ACTION can legitimately occur if a slow runner let the turn timeout fire and the
            // server's bot already played: our late move is simply obsolete, not a failure. The next
            // ViewUpdate carries the true state. Any other error is a real problem.
            is ErrorMessage ->
                if (message.code != ErrorCode.STALE_ACTION) error("server rejected a move mid-game: $message")
            else -> Unit
        }
    }
}
