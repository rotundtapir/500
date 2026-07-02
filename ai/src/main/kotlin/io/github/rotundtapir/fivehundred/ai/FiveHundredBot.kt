// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Strategy
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.ScoreSchedule
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.Trump
import io.github.rotundtapir.fivehundred.engine.teammatesOf
import kotlin.math.floor
import kotlin.random.Random

/**
 * A heuristic AI opponent for 500. Deliberately simple but sound: it never makes an illegal move and
 * plays sensible (if not expert) 500. The [Strategy] interface keeps it swappable for a stronger
 * engine (e.g. Monte-Carlo) later without touching call sites.
 *
 * Behaviour:
 *  - **Bidding** estimates tricks for the best denomination (trump length + the kitty + side aces/kings)
 *    and bids that level if it reaches 6; bids Misère on a suitably weak hand; otherwise passes.
 *  - **Kitty** keeps trumps and high cards for a suit contract (discards the weakest), and sheds the
 *    most dangerous high cards for a Misère.
 *  - **Play** wins tricks as cheaply as possible, lets a winning teammate be, dumps low otherwise,
 *    and (as the Misère declarer) plays to avoid taking tricks. Teammates come from the view's
 *    [PlayerView.playerCount], so the same bot plays 2-, 4- and 6-handed games.
 */
class FiveHundredBot(
    private val schedule: ScoreSchedule = ScoreSchedule.Avondale,
) : Strategy<PlayerView, Action> {

    override fun decide(view: PlayerView, random: Random): Action = when (view.phase) {
        Phase.BIDDING -> {
            val bid = proposeBid(view.hand, view.highBid)
            Action.PlaceBid(if (bid == Bid.Pass || bid in view.legalBids) bid else Bid.Pass)
        }
        Phase.KITTY -> {
            val c = view.contract!!
            Action.ExchangeKitty(chooseDiscards(view.hand, c.trump, c.isMisere))
        }
        Phase.PLAY -> Action.PlayCard(choosePlay(view))
        Phase.COMPLETE -> error("No action at COMPLETE")
    }

    // --- Bidding ---------------------------------------------------------------------------------

    /** The bid this bot would like to make given its [hand] and the current [highBid] (may be Pass). */
    fun proposeBid(hand: List<Card>, highBid: Bid?): Bid {
        val (bestTrump, estimate) = Trump.entries
            .map { it to estimateTricks(hand, it) }
            .maxBy { it.second }

        val candidates = buildList {
            val level = floor(estimate).toInt()
            if (level >= 6) add(Bid.Named(level.coerceAtMost(10), bestTrump))
            if (looksLikeMisere(hand)) add(Bid.Misere)
        }
        return candidates
            .filter { highBid == null || schedule.outranks(it, highBid) }
            .maxByOrNull { schedule.rank(it) }
            ?: Bid.Pass
    }

    /** Rough expected tricks for [trump]: trump length, +1 for the kitty, plus side-suit high cards. */
    private fun estimateTricks(hand: List<Card>, trump: Trump): Double {
        val eval = TrickEvaluator(trump)
        var tricks = hand.count { eval.isTrump(it) }.toDouble() + 1.0 // +1 for the kitty
        for (suit in Suit.entries) {
            if (trump.suit == suit) continue
            val inSuit = hand.filterIsInstance<SuitedCard>().filter { eval.effectiveSuit(it) == suit }
            if (inSuit.any { it.rank == Rank.ACE }) tricks += 1.0
            if (inSuit.any { it.rank == Rank.KING } && inSuit.size >= 2) tricks += 0.5
        }
        return tricks
    }

    private fun looksLikeMisere(hand: List<Card>): Boolean {
        if (hand.any { it is Joker }) return false // the Joker is forced to win a trick
        if (hand.any { it is SuitedCard && it.rank == Rank.ACE }) return false
        val kings = hand.count { it is SuitedCard && it.rank == Rank.KING }
        val highs = hand.count { it is SuitedCard && it.rank.ordinal >= Rank.QUEEN.ordinal }
        return kings == 0 && highs <= 1
    }

    // --- Kitty -----------------------------------------------------------------------------------

    /** Which [KITTY_SIZE] cards to discard from a 13-card hand after taking the kitty. */
    fun chooseDiscards(hand: List<Card>, trump: Trump, misere: Boolean): List<Card> {
        val eval = TrickEvaluator(trump)
        return if (misere) {
            hand.sortedByDescending { misereDanger(it) }.take(KITTY_SIZE)
        } else {
            hand.sortedBy { rawStrength(it, eval) }.take(KITTY_SIZE)
        }
    }

    private fun misereDanger(card: Card): Int = when (card) {
        is Joker -> 1000
        is SuitedCard -> card.rank.ordinal
    }

    // --- Play ------------------------------------------------------------------------------------

    /** The card this bot plays given the current [view]. Always one of `view.legalPlays`. */
    fun choosePlay(view: PlayerView): Card {
        val trump = view.trump ?: Trump.NO_TRUMP
        val eval = TrickEvaluator(trump)
        val legal = view.legalPlays
        check(legal.isNotEmpty()) { "No legal plays available" }
        val trick = view.currentTrick
        val ledSuit = view.ledSuit
        val leading = trick.isEmpty()
        val contract = view.contract

        // Misère declarer: take no tricks. Shed the highest card that still loses; if forced to win,
        // lose the least valuable card.
        if (contract != null && contract.isMisere && view.seat == contract.declarer) {
            if (leading) return legal.minBy { rawStrength(it, eval) }
            val topSoFar = trick.maxOf { eval.strength(it.card, ledSuit) }
            val losers = legal.filter { eval.strength(it, ledSuit) < topSoFar }
            return if (losers.isNotEmpty()) losers.maxBy { rawStrength(it, eval) }
            else legal.minBy { rawStrength(it, eval) }
        }

        if (leading) return legal.maxBy { rawStrength(it, eval) } // lead a strong card

        val best = trick.maxBy { eval.strength(it.card, ledSuit) }
        // No teammates at 2 players, one at 4, two at 6.
        val teammateWinning = best.seat in teammatesOf(view.seat, view.playerCount)
        val bestStrength = eval.strength(best.card, ledSuit)
        val winners = legal.filter { eval.strength(it, ledSuit) > bestStrength }

        return when {
            teammateWinning -> legal.minBy { rawStrength(it, eval) }        // let the teammate take it
            winners.isNotEmpty() -> winners.minBy { rawStrength(it, eval) } // win as cheaply as possible
            else -> legal.minBy { rawStrength(it, eval) }                   // can't win: dump the lowest
        }
    }

    /** A context-free strength for keep/dump decisions: trumps rank above all side cards. */
    private fun rawStrength(card: Card, eval: TrickEvaluator): Int =
        if (eval.isTrump(card)) maxOf(eval.strength(card, eval.trump.suit), 100)
        else (card as? SuitedCard)?.rank?.ordinal ?: 0
}
