// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.ai.RunningStat
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.Trump
import io.github.rotundtapir.fivehundred.engine.teamOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import kotlin.random.Random
import kotlin.test.Test

/**
 * Investigation harness (not a regression gate): is the advanced bot's willingness to bid
 * no-trumps *without the Joker* a sampling artifact of starved platforms, or something the model
 * genuinely believes in? For every seeded opening decision it compares a web-starved search
 * (16 worlds) against a saturated one (256), then for hands where even the saturated search opens
 * NT sans Joker it (a) estimates the NT-vs-Pass reward gap with 2000 paired determinizations and
 * (b) plays the real deal out to see whether the contract makes under heuristic play.
 *
 * `@Disabled` measurement harness (like StrengthSweepTest) — remove the annotation to rerun.
 * Last run (2026-07-21, seeds 1..400, 292 jokerless openings): NT-sans-Joker opened 3x at 16
 * worlds, 4x at 256; 16-vs-256 disagreement 15%; all 4 flagged hands had strongly positive EV
 * gaps (z 9.7-36.7) and made on the real deal.
 */
@Disabled("measurement harness — remove the annotation to rerun")
class NtBidInvestigationTest {

    private val rules = FiveHundredRules()
    private val heuristic = FiveHundredBot()

    private fun fixedConfig(worlds: Int) = SearchConfig(
        maxDeterminizations = worlds,
        minDeterminizations = worlds / 2,
        batchSize = 4,
        timeBudgetEnabled = false,
    )

    private fun openerView(seed: Long): PlayerView {
        val state = rules.newGame(seed)
        return rules.view(state, rules.currentActor(state)!!)
    }

    private fun ntBidOf(action: Action): Bid.Named? =
        ((action as? Action.PlaceBid)?.bid as? Bid.Named)?.takeIf { it.trump == Trump.NO_TRUMP }

    /** Mirror of the bot's evaluate(): apply [arm], heuristic plays the hand out, differential reward. */
    private fun rolloutReward(world: GameState, seat: Seat, arm: Action, random: Random): Double {
        val myTeam = teamOf(seat, world.teamCount)
        val startScores = world.scores
        val startHand = world.handNumber
        var s = rules.apply(world, seat, arm)
        while (s.phase != Phase.COMPLETE && s.handNumber == startHand) {
            val actor = rules.currentActor(s)!!
            s = rules.apply(s, actor, heuristic.decide(rules.view(s, actor), random))
        }
        fun delta(team: Int) = (s.scores[team] ?: 0) - (startScores[team] ?: 0)
        val bestOther = (0 until world.teamCount).filter { it != myTeam }.maxOf { delta(it) }
        return (delta(myTeam) - bestOther).toDouble()
    }

    /** Paired 2000-world estimate of mean reward for [arm] vs Pass from [view]. */
    private fun evGap(view: PlayerView, arm: Action, worlds: Int = 2000): Triple<Double, Double, Double> {
        val determinizer = Determinizer(rules.playerCount)
        val tracker = SeenTracker().also { it.observe(view) }
        val random = Random(view.hand.hashCode().toLong())
        val armStat = RunningStat()
        val passStat = RunningStat()
        val gapStat = RunningStat()
        val setup = determinizer.prepare(view, tracker)
        repeat(worlds) {
            val world = determinizer.sample(view, setup, random)
            val a = rolloutReward(world, view.seat, arm, random)
            val p = rolloutReward(world, view.seat, Action.PlaceBid(Bid.Pass), random)
            armStat.add(a)
            passStat.add(p)
            gapStat.add(a - p)
        }
        return Triple(armStat.mean, passStat.mean, gapStat.mean / maxOf(gapStat.standardError, 1e-9))
    }

    /** Plays the REAL deal out (auction from after the forced NT open, all heuristic). */
    private fun playRealDeal(seed: Long, ntBid: Bid.Named): String {
        var s = rules.newGame(seed)
        val opener = rules.currentActor(s)!!
        val random = Random(1)
        s = rules.apply(s, opener, Action.PlaceBid(ntBid))
        val startHand = s.handNumber
        while (s.phase != Phase.COMPLETE && s.handNumber == startHand) {
            val actor = rules.currentActor(s)!!
            s = rules.apply(s, actor, heuristic.decide(rules.view(s, actor), random))
        }
        val result = s.handResults.lastOrNull() ?: return "hand not scored (pass-out?)"
        return if (result.contract.declarer != opener || result.contract.bid != ntBid) {
            "overbid by ${result.contract.declarer} to ${result.contract.bid} (made=${result.made})"
        } else {
            "played: declarerTricks=${result.declarerTricks} made=${result.made}"
        }
    }

    @Test
    fun `NT-without-Joker opening bids - starved vs saturated search`() = runBlocking {
        val seeds = 1L..400L
        var ntNoJokerStarved = 0
        var ntNoJokerSaturated = 0
        var disagreements = 0
        var decisions = 0
        val flagged = mutableListOf<Pair<Long, Bid.Named>>()

        for (seed in seeds) {
            val view = openerView(seed)
            if (view.hand.any { it is Joker }) continue
            decisions++
            val starved = AdvancedBot(rules, config = fixedConfig(16)).decide(view, Random(seed))
            val saturated = AdvancedBot(rules, config = fixedConfig(256)).decide(view, Random(seed))
            if (ntBidOf(starved) != null) ntNoJokerStarved++
            ntBidOf(saturated)?.let {
                ntNoJokerSaturated++
                flagged += seed to it
            }
            if (starved != saturated) disagreements++
        }

        println("jokerless opening decisions: $decisions")
        println("NT bid at 16 worlds (web-starved): $ntNoJokerStarved")
        println("NT bid at 256 worlds (saturated):  $ntNoJokerSaturated")
        println("16-vs-256 decision disagreements:  $disagreements (${100 * disagreements / decisions}%)")
        println()
        println("--- flagged: saturated search opened NT without the Joker ---")
        for ((seed, bid) in flagged.take(12)) {
            val view = openerView(seed)
            val (evNt, evPass, gapZ) = evGap(view, Action.PlaceBid(bid))
            val outcome = playRealDeal(seed, bid)
            val handCodes = view.hand.joinToString(" ") { it.code }
            println("seed=$seed bid=${bid.level}NT hand=[$handCodes]")
            println(
                "  EV(bid)=${"%.1f".format(evNt)} EV(pass)=${"%.1f".format(evPass)} " +
                    "gap z=${"%.1f".format(gapZ)}  realDeal: $outcome",
            )
        }
    }
}
