// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Strategy
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
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
) : Player<PlayerView, Action> {

    /** The connection currently playing this seat, or null for a bot (empty seat or dropped human). */
    @Volatile
    var occupant: PlayerConnection? = null

    /** True for a seat that was empty at game start — a bot for the whole game, never reclaimable. */
    @Volatile
    var permanentBot: Boolean = false

    // Capacity 1, but DRAINED at the top of every decide() (see below). The buffer means submit() is
    // a non-suspending trySend that never depends on a receiver being parked at that exact instant
    // (a rendezvous channel does not reliably hand off to a select-registered receiver). Draining on
    // entry is what makes it safe: any action left over from a previous turn — e.g. one that raced
    // ahead of the room's state bookkeeping after a timeout already ran the bot — is discarded before
    // this turn waits, so a stale action can never be consumed as the answer to a *different* turn
    // (the room-wedging bug a plain capacity-1 buffer used to allow).
    private val responses = Channel<Action>(capacity = 1)

    // Wakes a parked decide() early when the occupant drops, so the table doesn't freeze for the
    // whole turn timeout waiting on someone who has already left. CONFLATED: a spare signal from a
    // previous turn just coalesces and is drained at the next decide() entry.
    private val interrupts = Channel<Unit>(capacity = Channel.CONFLATED)

    override suspend fun decide(view: PlayerView): Action {
        // Drain anything buffered from a prior turn (stale submits, a spent interrupt) so this turn
        // starts clean. A legit action for THIS turn can't be here yet: the client only sends after
        // seeing this turn's view, which is delivered after the driver has already entered decide().
        drain(responses)
        drain(interrupts)
        val conn = occupant
        if (permanentBot || conn == null || !conn.connected) {
            return bot.decide(view, botRandom)
        }
        // The client already has this view (the room fanned it out after the previous action); it
        // carries isMyTurn + the legal-action lists, so it is the turn prompt. Wait for the action,
        // falling back to the bot for this one turn if the human runs out the clock OR drops mid-turn
        // ([interrupt]). A timeout does NOT surrender the seat: the occupant stays connected and is
        // prompted again on their next turn. Only an actual socket drop evicts, via the room's
        // Disconnected handling, with reclaim on reconnect.
        val chosen: Action? = withTimeoutOrNull(turnTimeout) {
            select {
                responses.onReceive { it }
                interrupts.onReceive { null } // occupant dropped — hand this turn to the bot now
            }
        }
        return chosen ?: bot.decide(view, botRandom)
    }

    /** Nudge a parked [decide] to fall back to the bot immediately (the occupant just disconnected). */
    fun interrupt() {
        interrupts.trySend(Unit)
    }

    /** Discard everything currently buffered in [channel]. */
    private fun <T> drain(channel: Channel<T>) {
        var next = channel.tryReceive()
        while (next.isSuccess) next = channel.tryReceive()
    }

    /**
     * Hand a validated action to [decide]. Returns false only if the (capacity-1) buffer already
     * holds an un-consumed action — the network analogue of `trySubmit`. Anything buffered is
     * discarded at the next [decide] entry, so it can never answer a later turn.
     */
    fun submit(action: Action): Boolean = responses.trySend(action).isSuccess
}
