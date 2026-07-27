// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.ui.deal.dealTimings
import io.github.rotundtapir.cardkit.ui.pacing.PacingGates
import io.github.rotundtapir.cardkit.ui.pacing.TableTransitions
import io.github.rotundtapir.cardkit.ui.settings.AnimationSpeed
import io.github.rotundtapir.fivehundred.engine.HAND_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlinx.coroutines.flow.StateFlow

// 500's face of cardkit-ui's signal-driven pacing (cardkit.ui.pacing.PacingGates) and
// state-transition sound triggers: the adapter below projects a PlayerView onto the
// game-agnostic TableTransitions shape those read, with exactly the semantics 500's own
// PacingGates predicates used before the extraction.

/**
 * A [PlayerView] adapted to [TableTransitions]. A data class over the view so equality (and
 * therefore compose effect/remember keying) follows the view's own equality, exactly as when the
 * view was passed directly.
 */
private data class PlayerViewTransitions(private val view: PlayerView) : TableTransitions {
    override val handNumber: Int get() = view.handNumber
    override val trickNumber: Int get() = view.trickNumber
    override val trickCardCount: Int get() = view.currentTrick.size
    override val handResultCount: Int get() = view.handResults.size
    override val isHandStart: Boolean
        get() = view.phase == Phase.BIDDING && view.biddingHistory.isEmpty()
    override val awaitingHandResultAck: Boolean
        get() = view.lastHandResult != null && view.winner == null
}

/** This view, in the shape cardkit's pacing gates and table-sound triggers read. */
val PlayerView.transitions: TableTransitions get() = PlayerViewTransitions(this)

/** Slack added to the summed deal-animation stages when estimating the full deal pause. */
private const val PAUSE_SLACK_MILLIS = 250L

/**
 * Cardkit's [PacingGates] wired with 500's deal-pause estimate: the shuffle, flight and flip
 * budgets of the shared deal animation for a 10-card hand, plus a little slack. It only scales
 * the gates' deadlock backstop; the deal-done signal is what actually releases the first bidder.
 */
fun fiveHundredPacingGates(
    animationSpeed: StateFlow<AnimationSpeed>,
    holdTricks: StateFlow<Boolean>,
): PacingGates = PacingGates(animationSpeed, holdTricks) { speed ->
    if (speed == AnimationSpeed.OFF) {
        0L
    } else {
        dealTimings(speed).run { shuffleMillis + flyBudgetMillis + flipTotalMillis(HAND_SIZE) + PAUSE_SLACK_MILLIS }
    }
}
