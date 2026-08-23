// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.ScoreSchedule
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeedSearchTest {

    private val rules = FiveHundredRules()
    private val schedule = ScoreSchedule.Avondale
    private val bot = FiveHundredBot()

    @Test
    fun `finds a seed whose deal the bot would bid at the target level or above`() = runTest {
        val seed = SeedSearch.findSeedFor(predicate = SeedSearch.atLeastLevel(8))
        assertNotNull(seed, "no 8-level hand in the default attempt budget")
        // Verify against the engine directly rather than trusting the search's own predicate.
        val hand = rules.newGame(seed).hands.getValue(Seat(0))
        val floor = schedule.rank(Bid.Named(8, Trump.SPADES))
        assertTrue(
            bot.candidateBids(hand).filterIsInstance<Bid.Named>().any { schedule.rank(it) >= floor },
            "seed $seed does not actually deal an 8-level hand: $hand",
        )
    }

    @Test
    fun `is deterministic and returns the FIRST matching seed`() = runTest {
        val first = SeedSearch.findSeedFor(predicate = SeedSearch.atLeastLevel(8))
        assertNotNull(first)
        assertEquals(first, SeedSearch.findSeedFor(predicate = SeedSearch.atLeastLevel(8)))
        // Nothing before it may match, or "first" is a lie and re-shooting a board would drift.
        for (seed in 0 until first) {
            assertTrue(
                !SeedSearch.atLeastLevel(8)(rules.newGame(seed).hands.getValue(Seat(0))),
                "seed $seed matched but was skipped",
            )
        }
    }

    @Test
    fun `a target denomination is honoured, not just the level`() = runTest {
        val seed = SeedSearch.findSeedFor(predicate = SeedSearch.atLeastLevelIn(7, Trump.HEARTS))
        assertNotNull(seed, "no 7♥+ hand in the budget")
        val hand = rules.newGame(seed).hands.getValue(Seat(0))
        val floor = schedule.rank(Bid.Named(7, Trump.HEARTS))
        assertTrue(
            bot.candidateBids(hand)
                .filterIsInstance<Bid.Named>()
                .any { it.trump == Trump.HEARTS && schedule.rank(it) >= floor },
            "seed $seed is not a 7♥+ hand: $hand",
        )
    }

    @Test
    fun `a misere hand is found and is NOT what the level filter returns`() = runTest {
        val misereSeed = SeedSearch.findSeedFor(predicate = SeedSearch.wantsMisere())
        assertNotNull(misereSeed, "no misère hand in the budget")
        val hand = rules.newGame(misereSeed).hands.getValue(Seat(0))
        assertTrue(
            bot.candidateBids(hand).any { it == Bid.Misere || it == Bid.OpenMisere },
            "seed $misereSeed is not a misère hand: $hand",
        )
        // The level filter must not accept a misère-only hand just because Misère outranks 8♠ on
        // the ladder — the whole point of comparing against a Named bid rather than a raw rank.
        val namedOnly = bot.candidateBids(hand).filterIsInstance<Bid.Named>()
        if (namedOnly.isEmpty()) {
            assertTrue(!SeedSearch.atLeastLevel(8)(hand), "a misère-only hand must fail the level filter")
        }
    }

    @Test
    fun `an impossible predicate gives up rather than looping`() = runTest {
        assertNull(SeedSearch.findSeedFor(maxAttempts = 50) { false })
    }

    @Test
    fun `progress is reported while searching so a single-threaded UI can stay alive`() = runTest {
        var reports = 0
        SeedSearch.findSeedFor(maxAttempts = 1_000, onProgress = { reports++ }) { false }
        assertTrue(reports >= 3, "expected periodic progress, got $reports")
    }
}
