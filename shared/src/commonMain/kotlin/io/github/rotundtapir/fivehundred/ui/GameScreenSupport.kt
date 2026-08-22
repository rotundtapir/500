// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.Trump
import io.github.rotundtapir.fivehundred.engine.teamOf

/**
 * A completed trick sitting closed on the felt, judged from the view alone: play phase, no cards
 * in the current trick, a last trick to look at, and it isn't (yet) this player's turn. The felt's
 * hold (TrickArea) and the tutorial bubble's trick note both key on this — extracted so the two
 * can never drift apart (euchre's copies had, by three terms, before the same extraction there).
 */
internal fun PlayerView.hasClosedTrick(): Boolean =
    phase == Phase.PLAY && currentTrick.isEmpty() && lastTrick != null && !isMyTurn

/**
 * The hand in display order, memoized on exactly its inputs: the sort only changes on a new hand,
 * a play, or the trump being decided. One definition — the fan and the deal row previously carried
 * verbatim copies, memo key included.
 */
@Composable
internal fun rememberDisplayHand(view: PlayerView, sorted: Boolean): List<Card> =
    if (sorted) remember(view.hand, view.trump) { sortedForDisplay(view.hand, view.trump) } else view.hand

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
