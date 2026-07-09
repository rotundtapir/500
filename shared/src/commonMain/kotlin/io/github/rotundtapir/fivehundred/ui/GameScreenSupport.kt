// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.Trump
import io.github.rotundtapir.fivehundred.engine.teamOf

/**
 * Screen-space rects of the tutorial's interaction targets (a bid button, a card, the felt),
 * recorded by [tutorialTarget] as each element is laid out and read back by the tutorial bubble to
 * anchor itself. A stable holder over a snapshot map, passed instead of a raw mutable collection so
 * composable signatures stay stable and side-effect-free to read.
 */
@Stable
class TutorialAnchors {
    private val rects = mutableStateMapOf<String, Rect>()

    /** Record [key]'s current on-screen [rect] (called from layout). */
    fun record(key: String, rect: Rect) {
        rects[key] = rect
    }

    /** The rect last recorded for [key], or null if that target isn't on screen. */
    operator fun get(key: String): Rect? = rects[key]
}

internal fun seatLabel(view: PlayerView, botNames: Map<Seat, String>, seat: Seat): String =
    if (seat == view.seat) "You" else botNames[seat] ?: "Seat ${seat.index}"

/** The seats on [team], in seat order. */
private fun teamSeats(view: PlayerView, team: Int): List<Seat> =
    (0 until view.playerCount).map(::Seat).filter { teamOf(it, view.teamCount) == team }

/**
 * A short name for another team, built from its members ("Gus & Ivy") — used where "Them" is
 * ambiguous, i.e. whenever there is more than one opposing team.
 */
internal fun teamLabel(view: PlayerView, botNames: Map<Seat, String>, team: Int): String =
    teamSeats(view, team).joinToString(" & ") { seatLabel(view, botNames, it) }

/** Hand order for display: trumps (both bowers + Joker) first, then alternating-colour suits, strongest first. */
internal fun sortedForDisplay(hand: List<Card>, trump: Trump?): List<Card> {
    val eval = TrickEvaluator(trump ?: Trump.NO_TRUMP)
    val suitOrder = listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)
    return hand.sortedWith(
        compareBy(
            { card -> if (eval.isTrump(card)) 0 else 1 + suitOrder.indexOf(eval.effectiveSuit(card)) },
            { card -> -eval.strength(card, eval.effectiveSuit(card)) },
        ),
    )
}

/**
 * Records this composable's window bounds under [key] for the tutorial bubble to anchor to.
 * [widthFraction] narrows the recorded rect to the left fraction of the bounds — for cards in the
 * overlapping fan, where only the left strip of each card is actually visible, so the bubble's tail
 * points at what the player can see rather than at the covered remainder.
 */
internal fun Modifier.tutorialTarget(
    anchors: TutorialAnchors?,
    key: String,
    widthFraction: Float = 1f,
): Modifier =
    if (anchors == null) this else onGloballyPositioned { coords ->
        val bounds = coords.boundsInRoot()
        anchors.record(
            key,
            if (widthFraction >= 1f) {
                bounds
            } else {
                Rect(bounds.left, bounds.top, bounds.left + bounds.width * widthFraction, bounds.bottom)
            },
        )
    }
