// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Strategy
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration

/**
 * The server-side [Player] for one seat — the exact mirror of the app's `ChannelPlayer`, but backed
 * by the network. The [GameDriver] calls [decide] on the actor for each turn; this either plays the
 * bot (empty seat, or a human who dropped/idled) or prompts the human and waits for their action.
 *
 * Bot substitution and seat reclaim fall out of the driver loop for free: [occupant] is read at the
 * start of every [decide], so a disconnect makes the next turn bot-played and a reconnect (which
 * sets [occupant] back) makes the following turn human again. The reclaim happens at the turn
 * boundary because that is when [decide] next reads [occupant].
 */
class SeatHost(
    val seat: Seat,
    private val bot: Strategy<PlayerView, Action>,
    private val botRandom: Random,
    private val turnTimeout: Duration,
    /** Notifies the room that this seat just got bot-substituted (a human's turn timed out). */
    private val onTimedOut: suspend (Seat) -> Unit,
) : Player<PlayerView, Action> {

    /** The connection currently playing this seat, or null for a bot (empty seat or dropped human). */
    @Volatile
    var occupant: PlayerConnection? = null

    /** True for a seat that was empty at game start — a bot for the whole game, never reclaimable. */
    @Volatile
    var permanentBot: Boolean = false

    // Rendezvous: the human's submitted action is handed straight to the waiting decide().
    private val responses = Channel<Action>(Channel.RENDEZVOUS)

    override suspend fun decide(view: PlayerView): Action {
        val conn = occupant
        if (permanentBot || conn == null || !conn.connected) {
            return bot.decide(view, botRandom)
        }
        // The client already has this view (the room fanned it out after the previous action); it
        // carries isMyTurn + the legal-action lists, so it is the turn prompt. Just wait for the
        // action, falling back to the bot if the human runs out the clock.
        return withTimeoutOrNull(turnTimeout) { responses.receive() }
            ?: run {
                onTimedOut(seat)
                bot.decide(view, botRandom)
            }
    }

    /**
     * Hand a validated action to the waiting [decide]. Returns false if no turn is in progress (a
     * stale double-submit racing a state change) — the network analogue of `trySubmit`.
     */
    fun submit(action: Action): Boolean = responses.trySend(action).isSuccess
}
