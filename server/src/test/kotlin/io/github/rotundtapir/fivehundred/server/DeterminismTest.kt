// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.StrategyPlayer
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The server's authority rests on the engine being deterministic from a seed. This proves the
 * server-style wiring (a players map of bot hosts over [GameDriver]) preserves that: the same seed
 * yields an identical match, which is also what makes the dev-mode `seed` flag usable for e2e.
 */
class DeterminismTest {

    private suspend fun runGame(seed: Long, playerCount: Int, teamCount: Int): GameState {
        val rules = FiveHundredRules(playerCount = playerCount, teamCount = teamCount)
        val bot = FiveHundredBot()
        val players = (0 until playerCount).associate { i ->
            Seat(i) to StrategyPlayer(bot, Random(seed + i + 1))
        }
        return GameDriver(rules, players).play(rules.newGame(seed))
    }

    @Test
    fun `same seed yields an identical 4p match`() = runTest {
        val a = runGame(42, playerCount = 4, teamCount = 2)
        val b = runGame(42, playerCount = 4, teamCount = 2)
        assertEquals(a.winner, b.winner)
        assertEquals(a.scores, b.scores)
        assertEquals(a.handResults, b.handResults)
    }

    @Test
    fun `same seed yields an identical 6p three-team match`() = runTest {
        val a = runGame(123, playerCount = 6, teamCount = 3)
        val b = runGame(123, playerCount = 6, teamCount = 3)
        assertEquals(a.winner, b.winner)
        assertEquals(a.scores, b.scores)
    }
}
