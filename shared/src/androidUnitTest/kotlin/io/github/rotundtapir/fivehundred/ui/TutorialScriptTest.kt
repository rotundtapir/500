// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.GameViewModel
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.HandResult
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.Trump
import io.github.rotundtapir.fivehundred.engine.label
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The gate on the tutorial's hand-copied script: replays [tutorialSteps] against the real rules and
 * bots with exactly `GameViewModel.newGame`'s wiring (seed [TUTORIAL_SEED], 4 players, default
 * house rules, bots seeded seed+i). If an engine or bot change alters the seed's trace, these tests
 * fail — turning CLAUDE.md's manual "regenerate Tutorial.kt and the trace together" rule into CI.
 */
class TutorialScriptTest {

    private val human = Seat(0)

    private class Replay(
        val stepsConsumed: Int,
        val plays: List<TrickPlay>,
        val trump: Trump,
        val result: HandResult,
    ) {
        /** Winner of each of the 10 tricks, in order. */
        val trickWinners: List<Seat> by lazy {
            val eval = TrickEvaluator(trump)
            plays.chunked(4).map { eval.winner(it) }
        }
    }

    /**
     * Replays the scripted hand, asserting every scripted human action is legal at the moment the
     * engine prompts for it — the property the tutorial UI depends on (it enables only that action).
     */
    private fun replayScriptedHand(): Replay {
        val rules = FiveHundredRules(playerCount = 4)
        val bot = FiveHundredBot()
        val botRandoms = (1..3).associate { i -> Seat(i) to Random(TUTORIAL_SEED + i) }

        var state = rules.newGame(TUTORIAL_SEED)
        var stepIndex = 0
        val plays = mutableListOf<TrickPlay>()
        var trump: Trump? = null

        var guard = 0
        while (state.handNumber == 1 && state.phase != Phase.COMPLETE) {
            check(guard++ < 200) { "runaway replay — the hand never completed" }
            val seat = rules.currentActor(state) ?: fail("no actor in phase ${state.phase}")
            val view = rules.view(state, seat)
            val action: Action = if (seat == human) {
                when (val step = tutorialSteps.getOrNull(stepIndex++)) {
                    is TutorialStep.BidStep -> {
                        assertTrue(
                            step.bid in view.legalBids,
                            "scripted bid ${step.bid.label} is not legal at its prompt",
                        )
                        Action.PlaceBid(step.bid)
                    }
                    is TutorialStep.DiscardStep -> {
                        assertEquals(KITTY_SIZE, view.mustDiscard, "not at the kitty exchange")
                        assertTrue(
                            view.hand.containsAll(step.cards),
                            "scripted discards ${step.cards.map { it.label }} not all in hand",
                        )
                        Action.ExchangeKitty(step.cards)
                    }
                    is TutorialStep.PlayStep -> {
                        assertTrue(
                            step.card in view.legalPlays,
                            "scripted play ${step.card.label} (step $stepIndex) is not legal",
                        )
                        Action.PlayCard(step.card)
                    }
                    null -> fail("script exhausted but the engine still prompts the human")
                }
            } else {
                bot.decide(view, botRandoms.getValue(seat))
            }
            if (state.phase == Phase.PLAY) {
                trump = state.contract?.trump
                plays += TrickPlay(seat, (action as Action.PlayCard).card)
            }
            state = rules.apply(state, seat, action)
        }

        return Replay(
            stepsConsumed = stepIndex,
            plays = plays,
            trump = trump ?: fail("hand never reached PLAY"),
            result = state.lastHandResult ?: fail("hand 1 was never scored (passed out?)"),
        )
    }

    @Test
    fun `every scripted action is legal and the script covers the whole hand`() {
        val replay = replayScriptedHand()
        assertEquals(tutorialSteps.size, replay.stepsConsumed, "unused tutorial steps")
        assertEquals(40, replay.plays.size, "a 4-player hand is exactly 40 plays")
    }

    @Test
    fun `the human wins the auction with 7 spades and makes it with 9 tricks`() {
        val result = replayScriptedHand().result
        assertEquals(human, result.contract.declarer)
        assertEquals(Bid.Named(7, Trump.SPADES), result.contract.bid)
        assertTrue(result.made, "the scripted contract must be made")
        assertEquals(9, result.declarerTricks)
        // The documented score line: +140 for your side, +10 for theirs (1 defender trick × 10).
        assertEquals(mapOf(0 to 140, 1 to 10), result.teamDeltas)
    }

    @Test
    fun `trick winners match the story told by tutorialTrickNotes`() {
        // Notes 1-10 narrate: you win 1,2 and 4-8 (4 is the ruff); Olive (seat 1) wins 3;
        // your partner Mabel (seat 2) wins 9,10.
        val expected = listOf(0, 0, 1, 0, 0, 0, 0, 0, 2, 2).map(::Seat)
        assertEquals(expected, replayScriptedHand().trickWinners)
        assertEquals((1..10).toSet(), tutorialTrickNotes.keys, "one note per trick")
    }

    @Test
    fun `the tutorial seed draws the bot names the script narrates`() {
        // GameViewModel.newGame assigns seats 1..3 the first three names of the seeded shuffle.
        val names = GameViewModel.BOT_NAMES.shuffled(Random(TUTORIAL_SEED))
        assertEquals(listOf("Olive", "Mabel", "Edna"), names.take(3))
    }
}
