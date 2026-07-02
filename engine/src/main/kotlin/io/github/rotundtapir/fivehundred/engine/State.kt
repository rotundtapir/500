// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit

/** Phases of a match. Between hands the engine deals again and returns to [BIDDING]. */
enum class Phase { BIDDING, KITTY, PLAY, COMPLETE }

/**
 * Team index: even seats are team 0, odd seats team 1. This yields the standard partnerships at
 * every supported count — 2 players: each seat is its own team; 4: seats 0&2 vs 1&3; 6: two teams
 * of three, 0&2&4 vs 1&3&5.
 */
fun teamOf(seat: Seat): Int = seat.index % 2

/** The next seat clockwise at a table of [playerCount]. */
fun nextSeat(seat: Seat, playerCount: Int): Seat = Seat((seat.index + 1) % playerCount)

/**
 * The other seats on [seat]'s team, in seat order: empty at 2 players, the opposite seat at 4, the
 * two same-parity seats at 6.
 */
fun teammatesOf(seat: Seat, playerCount: Int): List<Seat> =
    (0 until playerCount).map(::Seat).filter { it != seat && teamOf(it) == teamOf(seat) }

/** The winning bid and who made it. */
data class Contract(val declarer: Seat, val bid: Bid) {
    val trump: Trump get() = (bid as? Bid.Named)?.trump ?: Trump.NO_TRUMP
    val level: Int get() = (bid as? Bid.Named)?.level ?: 0
    val isMisere: Boolean get() = bid is Bid.Misere || bid is Bid.OpenMisere
    val isOpen: Boolean get() = bid is Bid.OpenMisere
}

/** The live state of the auction. */
data class BiddingState(
    val history: List<Pair<Seat, Bid>> = emptyList(),
    val passed: Set<Seat> = emptySet(),
    val highBid: Bid? = null,
    val highBidder: Seat? = null,
    val toAct: Seat,
)

/** The most recently completed trick, kept so UIs can show it (and its winner) between tricks. */
data class CompletedTrick(val plays: List<TrickPlay>, val winner: Seat)

/** The scored outcome of one completed hand. */
data class HandResult(
    val contract: Contract,
    val declarerTricks: Int,
    val made: Boolean,
    val teamDeltas: Map<Int, Int>,
)

/**
 * The full, authoritative state of a 500 match. Immutable; transformed by [FiveHundredRules.apply].
 *
 * [rngSeed] evolves deterministically each deal so the whole match is reproducible from the initial
 * seed — useful for tests and a future authoritative server.
 */
data class GameState(
    val rngSeed: Long,
    val handNumber: Int,
    val dealer: Seat,
    val phase: Phase,
    val hands: Map<Seat, List<Card>>,
    val kitty: List<Card>,
    val bidding: BiddingState,
    val contract: Contract? = null,
    val activeSeats: List<Seat> = hands.keys.sortedBy { it.index },
    val exposedHands: Set<Seat> = emptySet(),
    val leader: Seat? = null,
    val currentTrick: List<TrickPlay> = emptyList(),
    val ledSuit: Suit? = null,
    val trickNumber: Int = 0,
    val tricksWon: Map<Seat, Int> = emptyMap(),
    val lastTrick: CompletedTrick? = null,
    val scores: Map<Int, Int> = mapOf(0 to 0, 1 to 0),
    val lastHandResult: HandResult? = null,
    val winner: Int? = null,
)

/** The redacted, per-seat projection handed to a [io.github.rotundtapir.cardkit.core.Player]. */
data class PlayerView(
    val seat: Seat,
    val phase: Phase,
    val playerCount: Int,
    val handNumber: Int,
    val hand: List<Card>,
    val handSizes: Map<Seat, Int>,
    val dealer: Seat,
    val scores: Map<Int, Int>,
    val toAct: Seat?,
    // Auction
    val biddingHistory: List<Pair<Seat, Bid>>,
    val highBid: Bid?,
    val highBidder: Seat?,
    val legalBids: List<Bid>,
    // Play
    val contract: Contract?,
    val trump: Trump?,
    val leader: Seat?,
    val currentTrick: List<TrickPlay>,
    val ledSuit: Suit?,
    val lastTrick: CompletedTrick?,
    val tricksWon: Map<Seat, Int>,
    val trickNumber: Int,
    val legalPlays: List<Card>,
    val mustDiscard: Int,
    val exposedDeclarerHand: List<Card>?,
    val activeSeats: List<Seat>,
    val lastHandResult: HandResult?,
    val winner: Int?,
) {
    val myTeam: Int get() = teamOf(seat)
    val isMyTurn: Boolean get() = toAct == seat
}

/** An action a seat can take. */
sealed interface Action {
    /** During [Phase.BIDDING]: place a bid (possibly [Bid.Pass]). */
    data class PlaceBid(val bid: Bid) : Action

    /** During [Phase.KITTY]: the declarer discards exactly [KITTY_SIZE] cards after taking the kitty. */
    data class ExchangeKitty(val discards: List<Card>) : Action

    /** During [Phase.PLAY]: play [card]; [nominate] names the led suit when leading the Joker at no-trump. */
    data class PlayCard(val card: Card, val nominate: Suit? = null) : Action
}
