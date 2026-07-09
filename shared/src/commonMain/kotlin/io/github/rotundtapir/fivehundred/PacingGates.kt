// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.ui.dealTimings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The signal-driven pacing that keeps bot turns (and, online, incoming server states) from racing
 * ahead of the on-screen animation. Extracted from [GameViewModel] so both the local game and the
 * online client apply the *same* gates: the gates are predicates on a view's shape, not on who is
 * deciding, so they pace a bot's turn locally and an incoming [PlayerView] online identically.
 *
 * Every mechanism here is inert at [AnimationSpeed.OFF] — the connected/UI test suites depend on it.
 */
class PacingGates(
    private val animationSpeed: StateFlow<AnimationSpeed>,
    private val holdTricks: StateFlow<Boolean>,
) {
    /** Highest hand number whose end-of-hand result dialog the player has dismissed. */
    private val handResultAcked = MutableStateFlow(0)

    /** Highest hand number whose shuffle/deal animation has finished on screen. */
    private val dealAnimationDone = MutableStateFlow(0)

    /** Key of the last completed trick the player has acknowledged (tapped past). */
    private val trickAcked = MutableStateFlow(0)

    /** Called by the UI when the hand-result dialog is dismissed; unblocks the next hand. */
    fun acknowledgeHandResult(handNumber: Int) {
        handResultAcked.value = maxOf(handResultAcked.value, handNumber)
    }

    /** Called by the UI when a hand's deal animation completes; releases the first bidder. */
    fun dealAnimationFinished(handNumber: Int) {
        dealAnimationDone.value = maxOf(dealAnimationDone.value, handNumber)
    }

    /** Called by the UI when the player taps the completed trick away; releases the next leader. */
    fun acknowledgeTrick(handNumber: Int, trickNumber: Int) {
        trickAcked.value = maxOf(trickAcked.value, trickKey(handNumber, trickNumber))
    }

    /** Reset all signals for a fresh game. */
    fun reset() {
        handResultAcked.value = 0
        dealAnimationDone.value = 0
        trickAcked.value = 0
    }

    /**
     * Mark everything about [view] as already acknowledged. Used when a state arrives that was *not*
     * animated into being — an online (re)connection snapshot — so the subsequent live views don't
     * block waiting for a deal-animation signal that will never come.
     */
    fun preAcknowledge(view: PlayerView) {
        acknowledgeHandResult(view.handNumber)
        dealAnimationFinished(view.handNumber)
        trickAcked.value = maxOf(trickAcked.value, trickKey(view.handNumber, view.trickNumber))
    }

    /** The cosmetic "thinking" beat applied before a bot's decision (or a bot-driven view online). */
    val botBeatMillis: Long get() = animationSpeed.value.botDelayMillis

    /**
     * Suspend until the UI is ready to reveal [view]: the hand's first bidder waits for the previous
     * result dialog to be dismissed and then for the shuffle/deal animation to finish; a fresh trick
     * waits (with "Hold tricks" on) until the player taps it away, or a short timed pause otherwise.
     */
    suspend fun awaitGates(view: PlayerView) {
        awaitDealGate(view)
        awaitTrickGate(view)
    }

    private suspend fun awaitDealGate(view: PlayerView) {
        if (view.phase != Phase.BIDDING || view.biddingHistory.isNotEmpty()) return
        if (view.lastHandResult != null && view.winner == null) {
            handResultAcked.first { it >= view.handNumber }
        }
        val speed = animationSpeed.value
        if (speed != AnimationSpeed.OFF) {
            // Signal, not timer, so slow devices can't start the auction mid-deal. The timeout is a
            // deadlock backstop (e.g. activity recreated / online snapshot with no deal animation).
            withTimeoutOrNull(dealPauseMillis(speed) * DEAL_BACKSTOP_FACTOR) {
                dealAnimationDone.first { it >= view.handNumber }
            }
            delay(speed.botDelayMillis)
        }
    }

    private suspend fun awaitTrickGate(view: PlayerView) {
        val speed = animationSpeed.value
        if (view.currentTrick.isNotEmpty() || view.trickNumber <= 0 || speed == AnimationSpeed.OFF) return
        if (holdTricks.value) {
            val key = trickKey(view.handNumber, view.trickNumber)
            combine(trickAcked, holdTricks) { acked, hold -> !hold || acked >= key }.first { it }
        } else {
            delay(interTrickPauseMillis(speed))
        }
    }

    private fun interTrickPauseMillis(speed: AnimationSpeed): Long = when (speed) {
        AnimationSpeed.SLOW -> 1800L
        AnimationSpeed.NORMAL -> 1000L
        AnimationSpeed.FAST -> 400L
        AnimationSpeed.OFF -> 0L
    }

    private fun dealPauseMillis(speed: AnimationSpeed): Long =
        if (speed == AnimationSpeed.OFF) {
            0L
        } else {
            dealTimings(speed).run { shuffleMillis + flyBudgetMillis + flipTotalMillis + PAUSE_SLACK_MILLIS }
        }

    private fun trickKey(handNumber: Int, trickNumber: Int) = handNumber * TRICK_KEY_STRIDE + trickNumber

    private companion object {
        const val PAUSE_SLACK_MILLIS = 250L
        const val DEAL_BACKSTOP_FACTOR = 3
        const val TRICK_KEY_STRIDE = 1000
    }
}
