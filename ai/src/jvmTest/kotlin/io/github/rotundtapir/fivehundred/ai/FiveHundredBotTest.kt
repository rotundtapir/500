// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.StrategyPlayer
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import io.github.rotundtapir.cardkit.core.of
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.Contract
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickPlay
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
    fun `bids no-trumps on a balanced stopper-rich hand`() {
        // Aces (and guarded kings) spread across every suit, but no suit long enough to be worth
        // trumping: no-trump ties the suits at level 6 and outranks them, so it should be chosen.
        val balanced = listOf(
            Rank.ACE of Suit.SPADES, Rank.KING of Suit.SPADES, Rank.FIVE of Suit.SPADES,
            Rank.ACE of Suit.HEARTS, Rank.KING of Suit.HEARTS, Rank.FIVE of Suit.HEARTS,
            Rank.ACE of Suit.DIAMONDS, Rank.FIVE of Suit.DIAMONDS,
            Rank.ACE of Suit.CLUBS, Rank.FIVE of Suit.CLUBS,
        )
        val bid = bot.proposeBid(balanced, highBid = null)
        assertTrue(bid is Bid.Named, "expected a named bid, got $bid")
        assertEquals(Trump.NO_TRUMP, (bid as Bid.Named).trump)
        assertTrue(bid.level >= 6)
    }

    @Test
    fun `bids open misere on a rock-bottom hand once the auction has passed seven`() {
        // Nothing above a 7 in any suit — safe to expose. Open Misère is only legal after the
        // auction reaches seven, so the bot must reach it through decide's legalBids filter.
        val rockBottom = listOf(
            Rank.FOUR of Suit.HEARTS, Rank.FIVE of Suit.HEARTS, Rank.SIX of Suit.HEARTS,
            Rank.FOUR of Suit.DIAMONDS, Rank.FIVE of Suit.DIAMONDS, Rank.SIX of Suit.DIAMONDS,
            Rank.FIVE of Suit.SPADES, Rank.SIX of Suit.SPADES,
            Rank.FIVE of Suit.CLUBS, Rank.SIX of Suit.CLUBS,
        )
        // Desirability alone (over a 7♠ high bid) already prefers Open Misère to plain Misère.
        assertEquals(Bid.OpenMisere, bot.proposeBid(rockBottom, highBid = Bid.Named(7, Trump.SPADES)))

        // ...and it is actually placed when the gate has opened and it is among the legal bids.
        val view = biddingView(
            hand = rockBottom,
            highBid = Bid.Named(7, Trump.SPADES),
            legalBids = listOf(Bid.Pass, Bid.Named(8, Trump.SPADES), Bid.Misere, Bid.OpenMisere),
        )
        assertEquals(Action.PlaceBid(Bid.OpenMisere), bot.decide(view, Random(0)))
    }

    // A minimal BIDDING-phase view for targeted decide tests: seat 0 opening or replying.
    private fun biddingView(
        hand: List<Card>,
        highBid: Bid?,
        legalBids: List<Bid>,
    ) = PlayerView(
        seat = Seat(0),
        phase = Phase.BIDDING,
        playerCount = 4,
        teamCount = 2,
        handNumber = 1,
        hand = hand,
        handSizes = emptyMap(),
        dealer = Seat(3),
        scores = emptyMap(),
        toAct = Seat(0),
        biddingHistory = emptyList(),
        highBid = highBid,
        highBidder = highBid?.let { Seat(1) },
        legalBids = legalBids,
        contract = null,
        trump = null,
        leader = Seat(0),
        currentTrick = emptyList(),
        ledSuit = null,
        lastTrick = null,
        tricksWon = emptyMap(),
        trickNumber = 0,
        legalPlays = emptyList(),
        mustDiscard = 0,
        exposedDeclarerHand = null,
        activeSeats = listOf(Seat(0), Seat(1), Seat(2), Seat(3)),
        lastHandResult = null,
        winner = null,
    )

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

    // A minimal PLAY-phase view for targeted choosePlay tests: seat 1 defending seat 0's Misère.
    private fun misereDefenceView(
        hand: List<Card>,
        trick: List<TrickPlay>,
        legal: List<Card> = hand,
    ) = PlayerView(
        seat = Seat(1),
        phase = Phase.PLAY,
        playerCount = 4,
        teamCount = 2,
        handNumber = 1,
        hand = hand,
        handSizes = emptyMap(),
        dealer = Seat(0),
        scores = emptyMap(),
        toAct = Seat(1),
        biddingHistory = emptyList(),
        highBid = Bid.Misere,
        highBidder = Seat(0),
        legalBids = emptyList(),
        contract = Contract(declarer = Seat(0), bid = Bid.Misere),
        trump = Trump.NO_TRUMP,
        leader = trick.firstOrNull()?.seat ?: Seat(1),
        currentTrick = trick,
        ledSuit = (trick.firstOrNull()?.card as? SuitedCard)?.suit,
        lastTrick = null,
        tricksWon = emptyMap(),
        trickNumber = 1,
        legalPlays = legal,
        mustDiscard = 0,
        exposedDeclarerHand = null,
        activeSeats = listOf(Seat(0), Seat(1), Seat(3)),
        lastHandResult = null,
        winner = null,
    )

    @Test
    fun `misere defender ducks under the declarer's card, shedding its highest safe card`() {
        // Declarer (seat 0) played the 8♥ to a heart trick. The defender holds Q♥, 7♥ and 4♥:
        // the Q would hand the trick to a defender, so shed the 7 — the biggest card that still
        // leaves the declarer's 8 winning.
        val hand = listOf(Rank.QUEEN of Suit.HEARTS, Rank.SEVEN of Suit.HEARTS, Rank.FOUR of Suit.HEARTS)
        val view = misereDefenceView(
            hand = hand,
            trick = listOf(
                TrickPlay(Seat(3), Rank.FIVE of Suit.HEARTS),
                TrickPlay(Seat(0), Rank.EIGHT of Suit.HEARTS),
            ),
        )
        assertEquals(Rank.SEVEN of Suit.HEARTS, bot.choosePlay(view))
    }

    @Test
    fun `misere defender leads low so the declarer cannot duck`() {
        val hand = listOf(Rank.ACE of Suit.SPADES, Rank.NINE of Suit.DIAMONDS, Rank.FIVE of Suit.CLUBS)
        val view = misereDefenceView(hand = hand, trick = emptyList())
        assertEquals(Rank.FIVE of Suit.CLUBS, bot.choosePlay(view))
    }

    @Test
    fun `misere defender forced over the declarer sheds its most dangerous card`() {
        // Declarer played the 4♥ and every legal card beats it — the trick can't stick to the
        // declarer, so dump the Ace while it costs nothing.
        val hand = listOf(Rank.ACE of Suit.HEARTS, Rank.SIX of Suit.HEARTS)
        val view = misereDefenceView(
            hand = hand,
            trick = listOf(
                TrickPlay(Seat(3), Rank.FIVE of Suit.HEARTS),
                TrickPlay(Seat(0), Rank.FOUR of Suit.HEARTS),
            ),
        )
        assertEquals(Rank.ACE of Suit.HEARTS, bot.choosePlay(view))
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
