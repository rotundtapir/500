// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.StrategyPlayer
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.of
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FiveHundredBotTest {
    private val rules = FiveHundredRules()
    private val bot = FiveHundredBot()

    @Test
    fun `bids a suit contract on a strong hand`() {
        val strong = listOf(
            Joker,
            Rank.JACK of Suit.SPADES,  // right bower
            Rank.JACK of Suit.CLUBS,   // left bower
            Rank.ACE of Suit.SPADES,
            Rank.KING of Suit.SPADES,
            Rank.QUEEN of Suit.SPADES,
            Rank.TEN of Suit.SPADES,
            Rank.ACE of Suit.HEARTS,
            Rank.FIVE of Suit.DIAMONDS,
            Rank.SIX of Suit.CLUBS,
        )
        val bid = bot.proposeBid(strong, highBid = null)
        assertTrue(bid is Bid.Named, "expected a named bid, got $bid")
        assertEquals(Trump.SPADES, (bid as Bid.Named).trump)
        assertTrue(bid.level >= 6)
    }

    @Test
    fun `bids misere on a weak scattered hand`() {
        val weak = listOf(
            Rank.FIVE of Suit.SPADES, Rank.SIX of Suit.SPADES,
            Rank.SEVEN of Suit.DIAMONDS, Rank.FIVE of Suit.DIAMONDS,
            Rank.FIVE of Suit.CLUBS, Rank.SIX of Suit.CLUBS,
            Rank.SEVEN of Suit.HEARTS, Rank.EIGHT of Suit.HEARTS,
            Rank.FOUR of Suit.HEARTS, Rank.SIX of Suit.DIAMONDS,
        )
        assertEquals(Bid.Misere, bot.proposeBid(weak, highBid = null))
    }

    @Test
    fun `passes when nothing outranks the current high bid`() {
        val weak = listOf(
            Rank.FIVE of Suit.SPADES, Rank.SIX of Suit.SPADES,
            Rank.SEVEN of Suit.DIAMONDS, Rank.FIVE of Suit.DIAMONDS,
            Rank.FIVE of Suit.CLUBS, Rank.SIX of Suit.CLUBS,
            Rank.SEVEN of Suit.HEARTS, Rank.EIGHT of Suit.HEARTS,
            Rank.FOUR of Suit.HEARTS, Rank.SIX of Suit.DIAMONDS,
        )
        assertEquals(Bid.Pass, bot.proposeBid(weak, highBid = Bid.OpenMisere))
    }

    @Test
    fun `keeps trumps and high cards, discards weakest for a suit contract`() {
        val hand = listOf(
            Rank.ACE of Suit.SPADES, Rank.KING of Suit.SPADES, Rank.QUEEN of Suit.SPADES, // trumps
            Rank.FOUR of Suit.DIAMONDS, Rank.FIVE of Suit.DIAMONDS, Rank.SIX of Suit.DIAMONDS,
            Rank.FIVE of Suit.CLUBS, Rank.SIX of Suit.CLUBS, Rank.SEVEN of Suit.CLUBS,
            Rank.FIVE of Suit.HEARTS, Rank.SIX of Suit.HEARTS, Rank.SEVEN of Suit.HEARTS,
            Rank.EIGHT of Suit.HEARTS,
        )
        val discards = bot.chooseDiscards(hand, Trump.SPADES, misere = false)
        assertEquals(3, discards.size)
        val eval = io.github.rotundtapir.fivehundred.engine.TrickEvaluator(Trump.SPADES)
        assertTrue(discards.none { eval.isTrump(it) }, "should not discard trumps")
        assertFalse((Rank.ACE of Suit.SPADES) in discards)
    }

    @Test
    fun `sheds the joker and aces when discarding for a misere`() {
        val hand = listOf(
            Joker, Rank.ACE of Suit.SPADES, Rank.ACE of Suit.HEARTS,
            Rank.FIVE of Suit.DIAMONDS, Rank.SIX of Suit.DIAMONDS, Rank.SEVEN of Suit.DIAMONDS,
            Rank.FIVE of Suit.CLUBS, Rank.SIX of Suit.CLUBS, Rank.SEVEN of Suit.CLUBS,
            Rank.FIVE of Suit.HEARTS, Rank.SIX of Suit.HEARTS, Rank.SEVEN of Suit.HEARTS,
            Rank.EIGHT of Suit.HEARTS,
        )
        val discards = bot.chooseDiscards(hand, Trump.NO_TRUMP, misere = true)
        assertTrue(Joker in discards)
        assertTrue((Rank.ACE of Suit.SPADES) in discards)
        assertTrue((Rank.ACE of Suit.HEARTS) in discards)
    }

    @Test
    fun `four bots play a full match making only legal moves`() = runTest {
        val players = (0 until rules.playerCount).associate { Seat(it) to StrategyPlayer(bot, Random(it.toLong())) }
        // Cap the match length so the test is bounded even if scores were to stall.
        val capped = object : GameRules<GameState, Action, PlayerView> by rules {
            override fun isTerminal(state: GameState): Boolean = rules.isTerminal(state) || state.handNumber > 60
        }
        // If any bot ever returned an illegal move, rules.apply would throw and fail the test.
        val terminal = GameDriver(capped, players).play(rules.newGame(seed = 2024L))

        assertNotNull(terminal.lastHandResult, "at least one contract should have been played out")
        if (terminal.phase == Phase.COMPLETE) {
            assertTrue(terminal.winner in setOf(0, 1))
            val winScore = terminal.scores[terminal.winner!!] ?: 0
            val loseScore = terminal.scores[1 - terminal.winner!!] ?: 0
            assertTrue(winScore >= 500 || loseScore <= -500, "winner reached the threshold")
        }
    }

    @Test
    fun `bots play full 2- and 6-player matches making only legal moves`() = runTest {
        for (playerCount in listOf(2, 6)) {
            val countRules = FiveHundredRules(playerCount = playerCount)
            val players = (0 until playerCount).associate { Seat(it) to StrategyPlayer(bot, Random(it.toLong())) }
            val capped = object : GameRules<GameState, Action, PlayerView> by countRules {
                override fun isTerminal(state: GameState): Boolean = countRules.isTerminal(state) || state.handNumber > 60
            }
            // If any bot ever returned an illegal move, rules.apply would throw and fail the test.
            val terminal = GameDriver(capped, players).play(countRules.newGame(seed = 2026L))
            assertNotNull(terminal.lastHandResult, "at $playerCount players at least one contract should complete")
        }
    }

    @Test
    fun `a bot-driven match is deterministic for a given seed`() = runTest {
        suspend fun run(): GameState {
            val players = (0 until rules.playerCount).associate { Seat(it) to StrategyPlayer(bot, Random(it.toLong())) }
            val capped = object : GameRules<GameState, Action, PlayerView> by rules {
                override fun isTerminal(state: GameState): Boolean = rules.isTerminal(state) || state.handNumber > 20
            }
            return GameDriver(capped, players).play(rules.newGame(seed = 77L))
        }
        assertEquals(run().scores, run().scores)
    }
}
