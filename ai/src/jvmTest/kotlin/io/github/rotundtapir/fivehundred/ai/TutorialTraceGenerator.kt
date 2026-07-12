// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.SuitedCard
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.HandResult
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.label
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * THROWAWAY GENERATOR — documents how the tutorial script in
 * `shared/src/commonMain/kotlin/io/github/rotundtapir/fivehundred/ui/Tutorial.kt` was produced.
 *
 * It replays seeds through the exact wiring `GameViewModel.newGame` uses
 * (FiveHundredRules(playerCount = 4) with default house rules, bots at seats 1..3 as
 * `StrategyPlayer(FiveHundredBot(), Random(seed + i))`, bot names from BOT_NAMES.shuffled(Random(seed)))
 * with a scripted human at seat 0 who bids a teachable 7/8-level suit contract. [generate] renders
 * the shipped seed's full trace to `build/tutorial-trace.txt`; [search] scans seeds 1..5000 for a
 * new candidate seed.
 *
 * Re-enable (remove @Disabled) and run with:
 *   ./gradlew :ai:jvmTest --tests "*TutorialTraceGenerator*"
 */
@org.junit.jupiter.api.Disabled("one-shot generator for the tutorial script; not a regression test")
class TutorialTraceGenerator {

    // Must match GameViewModel.BOT_NAMES exactly.
    private val botNamesPool = listOf(
        "Alice", "Bruce", "Clancy", "Daisy", "Edna", "Frank", "Gus", "Hazel",
        "Ivy", "Mabel", "Ned", "Olive", "Pearl", "Ray", "Thelma", "Wally",
    )

    private data class Trace(
        val seed: Long,
        val botNames: Map<Seat, String>,
        val initialHand: List<Card>,
        val kitty: List<Card>,
        val postKittyHand: List<Card>,
        val auction: List<Pair<Seat, Bid>>,
        val humanBid: Bid.Named,
        val discards: List<Card>,
        val tricks: List<Pair<List<TrickPlay>, Seat>>, // plays in order + winner
        val result: HandResult,
        val score: Int,
    )

    /**
     * Renders the trace of the shipped tutorial seed. The seed itself was picked by [search] under
     * the original "3 weakest off-suit" discard policy; issue #12 later upgraded the discard policy
     * in place (void the short diamond suit) while keeping the seed, so re-running [search] today
     * would prefer a different seed — the pin is deliberate.
     */
    @Test
    fun generate() {
        val trace = trySeed(9L) ?: error("seed 9 no longer satisfies the tutorial constraints")
        val text = render(trace)
        println(text)
        File("build/tutorial-trace.txt").apply { parentFile.mkdirs() }.writeText(text)
    }

    @Test
    @org.junit.jupiter.api.Disabled("seed search — only for picking a NEW tutorial seed")
    fun search() {
        val candidates = mutableListOf<Trace>()
        for (seed in 1L..5000L) {
            trySeed(seed)?.let { candidates += it }
        }
        check(candidates.isNotEmpty()) { "no seed in 1..5000 satisfied the tutorial constraints" }
        val best = candidates.maxByOrNull { it.score }!! // earliest seed wins ties
        println(render(best))
    }

    private fun trySeed(seed: Long): Trace? {
        val rules = FiveHundredRules(playerCount = 4)
        val bot = FiveHundredBot()
        val botRandoms = (1..3).associate { i -> Seat(i) to Random(seed + i) }
        val humanPlayRandom = Random(seed) // drives seat 0's PLAY decisions only
        val human = Seat(0)

        var state = rules.newGame(seed)
        var initialHand: List<Card>? = null
        var postKittyHand: List<Card>? = null
        var humanBid: Bid.Named? = null
        var discards: List<Card>? = null
        val plays = mutableListOf<TrickPlay>()

        var guard = 0
        while (state.handNumber == 1 && state.phase != Phase.COMPLETE) {
            check(guard++ < 200) { "runaway simulation at seed $seed" }
            val seat = rules.currentActor(state) ?: break
            val view = rules.view(state, seat)
            val action: Action = when (state.phase) {
                Phase.BIDDING -> {
                    if (seat == human) {
                        if (initialHand == null) initialHand = view.hand
                        if (humanBid == null) {
                            val bid = chooseHumanBid(view) ?: return null
                            humanBid = bid
                            Action.PlaceBid(bid)
                        } else {
                            Action.PlaceBid(Bid.Pass) // outbid — the seed will fail the win check
                        }
                    } else {
                        bot.decide(view, botRandoms.getValue(seat))
                    }
                }
                Phase.KITTY -> {
                    if (seat != human) return null // a bot won the auction
                    postKittyHand = view.hand
                    val d = chooseHumanDiscards(view) ?: return null
                    discards = d
                    Action.ExchangeKitty(d)
                }
                Phase.PLAY -> {
                    val r = if (seat == human) humanPlayRandom else botRandoms.getValue(seat)
                    bot.decide(view, r).also { a ->
                        plays += TrickPlay(seat, (a as Action.PlayCard).card)
                    }
                }
                Phase.COMPLETE -> return null
            }
            state = rules.apply(state, seat, action)
        }

        // Hand 1 is scored iff we are now in hand 2 with a result attached.
        val result = state.lastHandResult ?: return null
        val bid = humanBid ?: return null
        val hand0 = initialHand ?: return null
        val hand13 = postKittyHand ?: return null
        val d = discards ?: return null
        if (result.contract.declarer != human || result.contract.bid != bid || !result.made) return null
        if (plays.size != 40) return null

        val eval = TrickEvaluator(bid.trump)
        val tricks = plays.chunked(4).map { trick -> trick to eval.winner(trick) }

        var score = 0
        if (hand0.any { it is Joker }) score += 2
        if (hand0.any { it is SuitedCard && eval.isRightBower(it) }) score += 1

        // Re-run just the auction to capture its history (cheap, same determinism).
        val auction = replayAuction(seed, bid) ?: return null
        if (auction.any { (s, b) -> s != human && b != Bid.Pass }) score += 2

        val names = botNamesPool.shuffled(Random(seed))
        val botNames = (1..3).associate { i -> Seat(i) to names[i - 1] }

        return Trace(seed, botNames, hand0, hand13 - hand0.toSet(), hand13, auction, bid, d, tricks, result, score)
    }

    /** Replays only the bidding of [seed] (human places [humanBid] first turn) and returns the history. */
    private fun replayAuction(seed: Long, humanBid: Bid.Named): List<Pair<Seat, Bid>>? {
        val rules = FiveHundredRules(playerCount = 4)
        val bot = FiveHundredBot()
        val botRandoms = (1..3).associate { i -> Seat(i) to Random(seed + i) }
        var state = rules.newGame(seed)
        var placed = false
        while (state.phase == Phase.BIDDING && state.handNumber == 1) {
            val seat = rules.currentActor(state) ?: return null
            val view = rules.view(state, seat)
            val action = if (seat == Seat(0)) {
                Action.PlaceBid(if (placed) Bid.Pass else humanBid).also { placed = true }
            } else {
                bot.decide(view, botRandoms.getValue(seat))
            }
            state = rules.apply(state, seat, action)
            if (state.phase != Phase.BIDDING) return state.bidding.history
        }
        return null
    }

    /**
     * The tutorial human's bidding policy: find the suit where the hand is strongest (trump count
     * including bowers and the Joker); if it holds >= 5 such trumps, or the Joker plus the right
     * bower, bid the lowest legal 7-or-8-level bid in that suit. Otherwise skip the seed.
     */
    private fun chooseHumanBid(view: PlayerView): Bid.Named? {
        val hand = view.hand
        val suited = io.github.rotundtapir.fivehundred.engine.Trump.entries.filter { it.suit != null }
        val (bestTrump, count) = suited
            .map { t -> t to hand.count { TrickEvaluator(t).isTrump(it) } }
            .maxBy { it.second }
        val eval = TrickEvaluator(bestTrump)
        val hasJoker = hand.any { it is Joker }
        val hasRight = hand.any { eval.isRightBower(it) }
        val strong = count >= 5 || (hasJoker && hasRight)
        if (!strong) return null
        for (level in 7..8) {
            val bid = Bid.Named(level, bestTrump)
            if (bid in view.legalBids) return bid
        }
        return null
    }

    /**
     * Discard the 3 weakest off-suit (non-trump) cards, but manufacture a void when a side suit
     * is short enough: if an off-suit suit of 3 or fewer cards contains no ace, discard the whole
     * suit (the shortest such suit) and fill up with the lowest remaining off-suit cards. A void
     * turns small trumps into winners — ruff the suit when it's led. Null if the hand can't spare
     * 3 off-suit cards.
     */
    private fun chooseHumanDiscards(view: PlayerView): List<Card>? {
        val trump = view.contract?.trump ?: return null
        val eval = TrickEvaluator(trump)
        val offSuit = view.hand.filter { !eval.isTrump(it) }.filterIsInstance<SuitedCard>()
        if (offSuit.size < 3) return null
        val voidable = offSuit.groupBy { it.suit }.values
            .filter { suit -> suit.size <= 3 && suit.none { it.rank == Rank.ACE } }
            .minByOrNull { it.size }
            .orEmpty()
        val rest = (offSuit - voidable.toSet()).sortedBy { it.rank.ordinal }
        return voidable + rest.take(3 - voidable.size)
    }

    private fun render(t: Trace): String = buildString {
        appendLine("=== TUTORIAL TRACE seed=${t.seed} score=${t.score} ===")
        appendLine("Bot names: " + t.botNames.entries.joinToString { "seat ${it.key.index}=${it.value}" })
        appendLine("Human initial hand (10): " + t.initialHand.joinToString { it.label })
        appendLine("Kitty picked up: " + t.kitty.joinToString { it.label })
        appendLine("Post-kitty hand (13): " + t.postKittyHand.joinToString { it.label })
        appendLine("Auction:")
        t.auction.forEach { (seat, bid) ->
            val name = if (seat.index == 0) "You" else t.botNames.getValue(seat)
            appendLine("  seat ${seat.index} ($name): ${bid.label}")
        }
        appendLine("Human contract: ${t.humanBid.label}")
        appendLine("Discards: " + t.discards.joinToString { it.label })
        appendLine("Play (contract ${t.humanBid.label}):")
        t.tricks.forEachIndexed { i, (plays, winner) ->
            val playsText = plays.joinToString { p ->
                val name = if (p.seat.index == 0) "YOU" else t.botNames.getValue(p.seat)
                "$name:${p.card.label}"
            }
            val winName = if (winner.index == 0) "YOU" else t.botNames.getValue(winner)
            appendLine("  trick ${i + 1}: $playsText -> $winName")
        }
        appendLine("Result: made=${t.result.made} declarerTricks=${t.result.declarerTricks} deltas=${t.result.teamDeltas}")
    }
}
