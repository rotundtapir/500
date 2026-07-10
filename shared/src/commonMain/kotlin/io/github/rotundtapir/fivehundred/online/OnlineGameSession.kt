// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.online

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.PacingGates
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.net.ViewUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Turns the server's [ViewUpdate] stream into the same paced, animated experience the local game
 * has. Incoming views are queued (never back-pressuring the socket) and released through the shared
 * [PacingGates] so deals animate and completed tricks hold exactly as in a local game — the gates
 * are predicates on a view's shape, so they work identically here.
 *
 * Pacing is entirely client-side; the server never waits for animations. A "bot beat" is inserted
 * before a view only when the *previous* view's actor was not us, mirroring how the local game paces
 * a bot's move but echoes the human's own action instantly. The first view after a (re)connection is
 * a snapshot: it bypasses the gates (there was no deal animation to wait for) and pre-acknowledges
 * the pacing signals so the subsequent live views don't stall.
 */
class OnlineGameSession(
    private val pacing: PacingGates,
    private val scope: CoroutineScope,
) {
    private data class Queued(val update: ViewUpdate, val snapshot: Boolean)

    private val inbound = Channel<Queued>(Channel.UNLIMITED)

    private val _views = MutableStateFlow<PlayerView?>(null)

    /** The current view to render — published only after pacing gates release it. */
    val views: StateFlow<PlayerView?> = _views.asStateFlow()

    private val _turnRemainingMillis = MutableStateFlow<Long?>(null)

    /** Milliseconds left on the current turn clock (whoever's turn it is), or null when not ticking. */
    val turnRemainingMillis: StateFlow<Long?> = _turnRemainingMillis.asStateFlow()

    private val _authoritativeStateVersion = MutableStateFlow<Int?>(null)

    /**
     * The stateVersion of the latest *server* view — echoed back when submitting an action. Kept
     * separate from what is on screen so an optimistic (client-applied) view never changes the
     * version we submit against.
     */
    val authoritativeStateVersion: StateFlow<Int?> = _authoritativeStateVersion.asStateFlow()

    private var previousActor: Seat? = null
    private var consumer: Job? = null
    private var countdown: Job? = null

    // Bumped by reset(); a process() that was mid-beat when a reset happened checks this and drops its
    // now-stale view instead of publishing a finished game's state into a fresh session.
    private var generation = 0

    // Optimistic play: the human's move is shown immediately; the next server view (our echo) or an
    // error (reject) resolves it. [lastAuthoritative] is what we revert to on reject.
    private var optimisticPending = false
    private var lastAuthoritative: PlayerView? = null

    /** Start consuming queued views. Call once per game. */
    fun start() {
        consumer?.cancel()
        consumer = scope.launch {
            for (queued in inbound) process(queued)
        }
    }

    /** Enqueue a server view. [snapshot] is true for the first view after a (re)connection. */
    fun offer(update: ViewUpdate, snapshot: Boolean) {
        inbound.trySend(Queued(update, snapshot))
    }

    /** Clear state for a new game/rematch. */
    fun reset() {
        generation++
        pacing.reset()
        previousActor = null
        optimisticPending = false
        lastAuthoritative = null
        countdown?.cancel()
        // drop views still queued from the finished game
        var queued = inbound.tryReceive()
        while (queued.isSuccess) queued = inbound.tryReceive()
        _views.value = null
        _turnRemainingMillis.value = null
        _authoritativeStateVersion.value = null
    }

    /**
     * Show the human's own move immediately, before the server confirms it. Publishes [view] with no
     * gate/beat and stops the turn clock; the next server view replaces it (confirm) or [revert]
     * restores the last server view (reject). Does not touch [authoritativeStateVersion].
     */
    fun applyOptimistic(view: PlayerView) {
        optimisticPending = true
        countdown?.cancel()
        _turnRemainingMillis.value = null
        _views.value = view
        previousActor = view.toAct
    }

    /** Undo a pending optimistic move (the server rejected it), restoring the last server view. */
    fun revertOptimistic() {
        if (!optimisticPending) return
        optimisticPending = false
        lastAuthoritative?.let { _views.value = it }
    }

    private suspend fun process(queued: Queued) {
        val view = queued.update.view
        val gen = generation
        // Anchor the turn clock to when the view was *received*, before any bot-beat delay below, so
        // the countdown reflects the server's real deadline rather than lagging by the beat(s).
        val receivedAt = TimeSource.Monotonic.markNow()
        // Online, pacing must NOT gate rendering: the local game's gates hold a *bot's decision*
        // until an animation/tap signal, but here they would hold the *view* — and the signal only
        // fires once that view renders, so a hold-tricks/deal gate would deadlock with no backstop.
        // Instead we publish every view and just insert a short "beat" before someone else's move so
        // it is visible (own moves and reconnect snapshots publish instantly). GameScreen still runs
        // the deal animation and trick display off the published views.
        val instant = queued.snapshot || _views.value == null || optimisticPending
        if (!instant && previousActor != null && previousActor != view.seat) {
            delay(pacing.botBeatMillis)
        }
        if (gen != generation) return // reset() ran during the beat — this view belongs to a dead game
        // Publish the view and its version together (version *after* the beat), so an action submitted
        // while the previous view is still on screen is validated against that same on-screen state.
        lastAuthoritative = view
        _authoritativeStateVersion.value = queued.update.stateVersion
        optimisticPending = false
        _views.value = view
        previousActor = view.toAct
        startCountdown(queued.update.turnRemainingMillis, receivedAt)
    }

    private fun startCountdown(totalMillis: Long?, receivedAt: TimeMark) {
        countdown?.cancel()
        if (totalMillis == null) {
            _turnRemainingMillis.value = null
            return
        }
        countdown = scope.launch {
            // Anchor to the receive mark (a monotonic clock, so a throttled/backgrounded tab doesn't
            // accumulate drift, and the elapsed beat time is already accounted for).
            while (true) {
                val left = totalMillis - receivedAt.elapsedNow().inWholeMilliseconds
                _turnRemainingMillis.value = left.coerceAtLeast(0)
                if (left <= 0) break
                delay(COUNTDOWN_TICK_MILLIS)
            }
        }
    }

    private companion object {
        const val COUNTDOWN_TICK_MILLIS = 500L
    }
}
