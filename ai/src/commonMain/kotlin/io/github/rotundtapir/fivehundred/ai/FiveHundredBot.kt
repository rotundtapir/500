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
 *  - **Bidding** estimates makeable tricks for *every* denomination — suits by trump winners and
 *    length, no-trumps by stoppers (so NT competes on balanced hands) — and offers a bid for each
 *    that reaches 6, plus Misère / Open Misère on suitably weak hands. It then places the
 *    highest-ranked contract that is legal here, or passes. Every bid family is reachable.
 *  - **Kitty** keeps trumps and high cards for a suit contract (discards the weakest), and sheds the
 *    most dangerous high cards for a Misère.
 *  - **Play** wins tricks as cheaply as possible, lets a winning teammate be, dumps low otherwise,
 *    and (as the Misère declarer) plays to avoid taking tricks. Teammates come from the view's
 *    [PlayerView.playerCount] and [PlayerView.teamCount], so the same bot plays 2-, 4- and 6-handed
 *    games in any team structure.
 */
class FiveHundredBot(
    private val schedule: ScoreSchedule = ScoreSchedule.Avondale,
) : Strategy<PlayerView, Action> {

    override fun decide(view: PlayerView, random: Random): Action = when (view.phase) {
        Phase.BIDDING -> {
            // Bid the highest-ranked contract we fancy that is actually legal here — this routes
            // Misère/Open Misère through the "misère after seven" gate and any house-rule toggles,
            // and falls back to the next-best legal bid rather than collapsing straight to Pass.
            val bid = candidateBids(view.hand).filter { it in view.legalBids }
                .maxByOrNull { schedule.rank(it) } ?: Bid.Pass
            Action.PlaceBid(bid)
        }
        Phase.KITTY -> {
            val c = view.contract!!
            Action.ExchangeKitty(chooseDiscards(view.hand, c.trump, c.isMisere))
        }
        Phase.PLAY -> Action.PlayCard(choosePlay(view))
        Phase.COMPLETE -> error("No action at COMPLETE")
    }

    // --- Bidding ---------------------------------------------------------------------------------

    /**
     * The bid this bot would like to make given its [hand] and the current [highBid] (may be `null`):
     * the highest-ranked contract it fancies that still outranks [highBid], or [Bid.Pass].
     *
     * This weighs *desirability* only and ignores table legality (house-rule toggles and the
     * "misère after seven" gate); [decide] additionally filters against [PlayerView.legalBids].
     */
    fun proposeBid(hand: List<Card>, highBid: Bid?): Bid =
        candidateBids(hand)
            .filter { highBid == null || schedule.outranks(it, highBid) }
            .maxByOrNull { schedule.rank(it) }
            ?: Bid.Pass

    /**
     * Every contract this [hand] is worth bidding, unranked and ignoring table legality: a
     * [Bid.Named] for each denomination it estimates it can make (level >= 6, so **no-trumps
     * competes on equal footing with the suits**), plus [Bid.Misere] / [Bid.OpenMisere] on suitably
     * weak hands. Callers pick the winner with [ScoreSchedule.rank], which prefers the
     * higher-scoring denomination when trick estimates tie.
     */
    private fun candidateBids(hand: List<Card>): List<Bid> = buildList {
        for (trump in Trump.entries) {
            val level = floor(estimateTricks(hand, trump)).toInt()
            if (level >= 6) add(Bid.Named(level.coerceAtMost(10), trump))
        }
        // Both variants are offered when the stronger one qualifies, so that if Open Misère is
        // illegal here (gate not reached, or disabled) plain Misère can still be chosen.
        if (looksLikeMisere(hand)) add(Bid.Misere)
        if (looksLikeOpenMisere(hand)) add(Bid.OpenMisere)
    }

    /** Expected tricks for [trump], dispatching to the suit or no-trump model. */
    private fun estimateTricks(hand: List<Card>, trump: Trump): Double =
        if (trump.isNoTrump) estimateNoTrumpTricks(hand) else estimateSuitTricks(hand, trump)

    /**
     * Suit contract: trump *winners* (honours count fully, low trumps half) + the kitty, plus
     * side-suit aces/kings at a **ruff discount** — off-suit winners can be trumped, so they are
     * worth less than the same cards would be at no-trump.
     */
    private fun estimateSuitTricks(hand: List<Card>, trump: Trump): Double {
        val eval = TrickEvaluator(trump)
        val trumps = hand.filter { eval.isTrump(it) }
        val honours = trumps.count { isTrumpHonor(it, eval) }
        val low = trumps.size - honours
        // Honours are near-sure winners; low trumps win about half the time, plus a length bonus for
        // a long suit (once trumps are drawn the small ones start to win). + 1 for the kitty.
        var tricks = honours + 0.5 * low + 0.5 * maxOf(0, trumps.size - 4) + 1.0
        val bySuit = hand.filterIsInstance<SuitedCard>().groupBy { eval.effectiveSuit(it) }
        for (suit in Suit.entries) {
            if (trump.suit == suit) continue
            val inSuit = bySuit[suit].orEmpty()
            if (inSuit.any { it.rank == Rank.ACE }) tricks += 0.9
            if (inSuit.any { it.rank == Rank.KING } && inSuit.size >= 2) tricks += 0.4
        }
        return tricks
    }

    /**
     * No-trump: the Joker (always a winner here) + the kitty, plus each suit's top cards at full
     * value (nothing can be ruffed) and a length allowance for long suits once the honours are gone.
     */
    private fun estimateNoTrumpTricks(hand: List<Card>): Double {
        var tricks = 1.0 // kitty
        if (hand.any { it is Joker }) tricks += 1.0
        val bySuit = hand.filterIsInstance<SuitedCard>().groupBy { it.suit }
        for (suit in Suit.entries) {
            val inSuit = bySuit[suit].orEmpty()
            if (inSuit.isEmpty()) continue
            if (inSuit.any { it.rank == Rank.ACE }) tricks += 1.0
            if (inSuit.any { it.rank == Rank.KING } && inSuit.size >= 2) tricks += 0.5
            if (inSuit.any { it.rank == Rank.QUEEN } && inSuit.size >= 3) tricks += 0.25
            if (inSuit.size >= 4) tricks += (inSuit.size - 3) * 0.5
        }
        return tricks
    }

    /** A "sure" trump winner: the Joker, either bower, or the A/K/Q of the trump suit. */
    private fun isTrumpHonor(card: Card, eval: TrickEvaluator): Boolean = when {
        card is Joker -> true
        eval.isRightBower(card) || eval.isLeftBower(card) -> true
        card is SuitedCard -> eval.isTrump(card) && card.rank.ordinal >= Rank.QUEEN.ordinal
        else -> false
    }

    private fun looksLikeMisere(hand: List<Card>): Boolean {
        if (hand.any { it is Joker }) return false // the Joker is forced to win a trick
        if (hand.any { it is SuitedCard && it.rank == Rank.ACE }) return false
        val kings = hand.count { it is SuitedCard && it.rank == Rank.KING }
        val highs = hand.count { it is SuitedCard && it.rank.ordinal >= Rank.QUEEN.ordinal }
        return kings == 0 && highs <= 1
    }

    /**
     * A hand safe enough for **Open Misère** (played with the declarer's hand exposed): everything
     * [looksLikeMisere] wants, and no card high enough to be forced to win even when the opponents
     * can see it. Deliberately conservative — Open Misère is the rarest and riskiest bid.
     */
    private fun looksLikeOpenMisere(hand: List<Card>): Boolean =
        looksLikeMisere(hand) &&
            hand.all { it is SuitedCard && it.rank.ordinal <= Rank.SEVEN.ordinal }

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
    // Guard-clause dispatch across the play strategies (misère declarer/defender, lead, follow):
    // each returns early, which reads more clearly than one nested expression despite tripping the
    // complexity/return thresholds.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
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

        // Misère defender: the only goal is to force the declarer to take a trick, and taking
        // tricks ourselves costs nothing. Before the declarer has played, keep the trick as LOW
        // as possible (they duck under whatever is winning); once the declarer's card is down,
        // shed the highest card that still stays below it, so their card keeps the trick.
        if (contract != null && contract.isMisere && view.seat != contract.declarer) {
            val declarerPlay = trick.firstOrNull { it.seat == contract.declarer }
            return if (declarerPlay == null) {
                legal.minBy { rawStrength(it, eval) }
            } else {
                val target = eval.strength(declarerPlay.card, ledSuit)
                legal.filter { eval.strength(it, ledSuit) < target }
                    .maxByOrNull { rawStrength(it, eval) }
                    // Every legal card overtakes the declarer, so this trick can't stick to them —
                    // shed the most dangerous card while it's free.
                    ?: legal.maxBy { rawStrength(it, eval) }
            }
        }

        if (leading) return legal.maxBy { rawStrength(it, eval) } // lead a strong card

        val best = trick.maxBy { eval.strength(it.card, ledSuit) }
        // No teammates at 2 players, one at 4, one or two at 6 depending on the team structure.
        val teammateWinning = best.seat in teammatesOf(view.seat, view.playerCount, view.teamCount)
        val bestStrength = eval.strength(best.card, ledSuit)
        val winners = legal.filter { eval.strength(it, ledSuit) > bestStrength }

        return when {
            teammateWinning -> legal.minBy { rawStrength(it, eval) }        // let the teammate take it
            winners.isNotEmpty() -> winners.minBy { rawStrength(it, eval) } // win as cheaply as possible
            else -> legal.minBy { rawStrength(it, eval) }                   // can't win: dump the lowest
        }
    }

    /**
     * A context-free strength for keep/dump decisions: trumps rank above all side cards
     * (TrickEvaluator scores every trump at 100+, above any side card's rank ordinal; the led
     * suit never affects a trump's strength).
     */
    private fun rawStrength(card: Card, eval: TrickEvaluator): Int =
        if (eval.isTrump(card)) eval.strength(card, null)
        else (card as? SuitedCard)?.rank?.ordinal ?: 0
}
