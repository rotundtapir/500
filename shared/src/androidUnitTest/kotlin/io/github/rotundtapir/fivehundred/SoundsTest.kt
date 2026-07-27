// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.ui.SoundEffect
import io.github.rotundtapir.cardkit.ui.pacing.soundEffectsFor
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [soundEffectsFor], the pure trigger logic behind `GameSoundEffects`. Views are taken
 * from a real seeded game so the transitions (a card landing, a trick closing, a hand scoring) are
 * the ones the UI actually observes.
 */
class SoundsTest {

    /** Seat-0 views after each engine step of a seeded 4-player game, in order. */
    private fun seatViews(seed: Long, maxSteps: Int = 200): List<PlayerView> {
        val rules = FiveHundredRules(playerCount = 4)
        val bot = FiveHundredBot()
        val randoms = (0 until 4).associate { Seat(it) to Random(seed + it) }
        var state = rules.newGame(seed)
        val views = mutableListOf(rules.view(state, Seat(0)))
        var guard = 0
        while (state.phase != Phase.COMPLETE && state.handNumber <= 2 && guard++ < maxSteps) {
            val seat = rules.currentActor(state) ?: break
            state = rules.apply(state, seat, bot.decide(rules.view(state, seat), randoms.getValue(seat)))
            views += rules.view(state, Seat(0))
        }
        return views
    }

    @Test
    fun `a null on either side of the transition triggers nothing`() {
        val some = seatViews(2024L).first()
        assertEquals(emptyList(), soundEffectsFor(null, some.transitions))
        assertEquals(emptyList(), soundEffectsFor(some.transitions, null))
        assertEquals(emptyList(), soundEffectsFor(null, null))
    }

    @Test
    fun `each transition fires exactly the effects its field deltas imply`() {
        val views = seatViews(2024L)
        assertTrue(views.size > 20, "the game should produce a long run of views")
        var sawCardPlace = false
        var sawTrickTaken = false
        var sawScore = false
        views.zipWithNext { prev, next ->
            val effects = soundEffectsFor(prev.transitions, next.transitions)
            assertEquals(
                next.currentTrick.size > prev.currentTrick.size,
                SoundEffect.CARD_PLACE in effects,
                "CARD_PLACE must fire iff the current trick grew",
            )
            assertEquals(
                next.trickNumber > prev.trickNumber,
                SoundEffect.TRICK_TAKEN in effects,
                "TRICK_TAKEN must fire iff the trick number advanced",
            )
            assertEquals(
                next.handResults.size > prev.handResults.size,
                SoundEffect.SCORE in effects,
                "SCORE must fire iff a hand was newly scored",
            )
            sawCardPlace = sawCardPlace || SoundEffect.CARD_PLACE in effects
            sawTrickTaken = sawTrickTaken || SoundEffect.TRICK_TAKEN in effects
            sawScore = sawScore || SoundEffect.SCORE in effects
        }
        // The run spans a full hand, so every effect should have been exercised at least once.
        assertTrue(sawCardPlace, "a full hand should place cards")
        assertTrue(sawTrickTaken, "a full hand should complete tricks")
        assertTrue(sawScore, "reaching hand 2 means hand 1 scored")
    }

    @Test
    fun `a repeated hand result would still fire SCORE by count not value`() {
        // Two views identical except handResults grew by one entry equal to the previous entry:
        // the count-based trigger fires (a value comparison would miss it — the old bug).
        val views = seatViews(2024L)
        val afterFirstScore = views.first { it.handResults.isNotEmpty() }
        val doubled = afterFirstScore.copy(
            handResults = afterFirstScore.handResults + afterFirstScore.handResults.last(),
        )
        assertTrue(SoundEffect.SCORE in soundEffectsFor(afterFirstScore.transitions, doubled.transitions))
    }
}
