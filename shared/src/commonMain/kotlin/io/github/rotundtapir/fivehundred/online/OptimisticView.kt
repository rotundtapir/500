// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.online

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.nextSeat

/**
 * Purely visual projections of the human's own move, shown instantly before the server confirms
 * (see [OnlineGameSession.applyOptimistic]). They deliberately do NOT try to reproduce the engine —
 * only the immediate feedback (card leaves the hand, appears on the table, it stops being our turn).
 * The authoritative server view that arrives a moment later replaces this entirely, so anything not
 * modelled here (trick winners, whose turn is truly next, scoring) is corrected then.
 */

/** Best-effort next seat, only used to make it read as "not your turn" until the server view lands. */
private fun PlayerView.nextActor(): Seat = nextSeat(seat, playerCount)

fun PlayerView.withOptimisticPlay(card: Card, nominate: Suit?): PlayerView = copy(
    hand = hand - card,
    currentTrick = currentTrick + TrickPlay(seat, card, nominate),
    legalPlays = emptyList(),
    toAct = nextActor(),
)

fun PlayerView.withOptimisticBid(bid: Bid): PlayerView = copy(
    biddingHistory = biddingHistory + (seat to bid),
    legalBids = emptyList(),
    toAct = nextActor(),
)

fun PlayerView.withOptimisticDiscard(discards: List<Card>): PlayerView = copy(
    hand = hand - discards.toSet(),
    mustDiscard = 0,
    toAct = nextActor(),
)
