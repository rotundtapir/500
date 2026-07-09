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

    private val _stateVersion = MutableStateFlow<Int?>(null)

    /** The stateVersion of the currently rendered view — echoed back when submitting an action. */
    val stateVersion: StateFlow<Int?> = _stateVersion.asStateFlow()

    private var previousActor: Seat? = null
    private var consumer: Job? = null
    private var countdown: Job? = null

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
        pacing.reset()
        previousActor = null
        countdown?.cancel()
        _views.value = null
        _turnRemainingMillis.value = null
        _stateVersion.value = null
    }

    private suspend fun process(queued: Queued) {
        val view = queued.update.view
        if (queued.snapshot) {
            pacing.preAcknowledge(view)
        } else {
            pacing.awaitGates(view)
            // Beat before a move made by someone else so it is visible; our own action echoes now.
            if (previousActor != null && previousActor != view.seat) {
                delay(pacing.botBeatMillis)
            }
        }
        _views.value = view
        _stateVersion.value = queued.update.stateVersion
        previousActor = view.toAct
        startCountdown(queued.update.turnRemainingMillis)
    }

    private fun startCountdown(totalMillis: Long?) {
        countdown?.cancel()
        if (totalMillis == null) {
            _turnRemainingMillis.value = null
            return
        }
        countdown = scope.launch {
            // Anchor to a monotonic mark so a throttled (backgrounded) tab doesn't accumulate drift.
            val mark = TimeSource.Monotonic.markNow()
            while (true) {
                val left = totalMillis - mark.elapsedNow().inWholeMilliseconds
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
