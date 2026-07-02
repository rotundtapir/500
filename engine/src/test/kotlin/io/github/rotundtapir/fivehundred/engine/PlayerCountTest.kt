// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Behaviour that varies with the table size: 2-, 4- and 6-player games. */
class PlayerCountTest {

    private fun rules(playerCount: Int) = FiveHundredRules(playerCount = playerCount)

    // --- Construction ----------------------------------------------------------------------------

    @Test
    fun `only 2, 4 and 6 players are supported`() {
        for (count in listOf(2, 4, 6)) {
            assertEquals(count, rules(count).playerCount)
        }
        for (count in listOf(0, 1, 3, 5, 7, 8)) {
            assertThrows<IllegalArgumentException> { rules(count) }
        }
    }

    // --- Teams -----------------------------------------------------------------------------------

    @Test
    fun `teamOf puts even seats on team 0 and odd seats on team 1 at every count`() {
        for (count in listOf(2, 4, 6)) {
            for (i in 0 until count) {
                assertEquals(i % 2, teamOf(Seat(i)))
            }
        }
    }

    @Test
    fun `teammatesOf is empty at 2, the opposite seat at 4, two seats at 6`() {
        assertEquals(emptyList(), teammatesOf(Seat(0), 2))
        assertEquals(emptyList(), teammatesOf(Seat(1), 2))

        assertEquals(listOf(Seat(2)), teammatesOf(Seat(0), 4))
        assertEquals(listOf(Seat(3)), teammatesOf(Seat(1), 4))
        assertEquals(listOf(Seat(0)), teammatesOf(Seat(2), 4))
        assertEquals(listOf(Seat(1)), teammatesOf(Seat(3), 4))

        assertEquals(listOf(Seat(2), Seat(4)), teammatesOf(Seat(0), 6))
        assertEquals(listOf(Seat(1), Seat(5)), teammatesOf(Seat(3), 6))
        assertEquals(listOf(Seat(1), Seat(3)), teammatesOf(Seat(5), 6))
    }

    @Test
    fun `nextSeat wraps at the table size`() {
        assertEquals(Seat(0), nextSeat(Seat(1), 2))
        assertEquals(Seat(0), nextSeat(Seat(3), 4))
        assertEquals(Seat(5), nextSeat(Seat(4), 6))
        assertEquals(Seat(0), nextSeat(Seat(5), 6))
    }

    // --- Dealing ---------------------------------------------------------------------------------

    @Test
    fun `every count deals 10 to each seat with a 3-card kitty, all cards distinct and from the deck`() {
        for (count in listOf(2, 4, 6)) {
            val s = rules(count).newGame(seed = 17L)
            assertEquals(count, s.hands.size, "at $count players")
            s.hands.values.forEach { assertEquals(HAND_SIZE, it.size, "at $count players") }
            assertEquals(KITTY_SIZE, s.kitty.size, "at $count players")

            val dealt = s.hands.values.flatten() + s.kitty
            assertEquals(count * HAND_SIZE + KITTY_SIZE, dealt.toSet().size, "cards must be distinct")
            assertTrue(fiveHundredDeck(count).containsAll(dealt), "cards must come from the $count-player deck")

            // PlayerView reports the table size and hand number for every seat.
            for (i in 0 until count) {
                val view = rules(count).view(s, Seat(i))
                assertEquals(count, view.playerCount)
                assertEquals(1, view.handNumber)
                assertEquals(HAND_SIZE, view.hand.size)
            }
        }
    }

    @Test
    fun `at 2 players 20 cards are dead and only the dealt 23 ever enter play`() {
        val rules = rules(2)
        var s = rules.newGame(seed = 3L)
        val dealt: Set<Card> = (s.hands.values.flatten() + s.kitty).toSet()
        assertEquals(23, dealt.size) // 2 × 10 + kitty; the other 20 are dropped from the state

        val seen = mutableSetOf<Card>()
        while (!rules.isTerminal(s) && s.handNumber == 1) {
            val actor = rules.currentActor(s)!!
            s = rules.apply(s, actor, policy(rules.view(s, actor)))
            if (s.handNumber == 1) { // the final play deals hand 2, whose cards are a fresh shuffle
                s.lastTrick?.plays?.forEach { seen += it.card }
                seen += s.kitty // after the exchange the kitty holds the declarer's discards
                seen += s.hands.values.flatten()
            }
        }
        assertTrue(dealt.containsAll(seen), "a dead card entered play: ${seen - dealt}")
    }

    // --- Misère sit-outs -------------------------------------------------------------------------

    @Test
    fun `misere sit-outs - nobody at 2, the partner at 4, both teammates at 6`() {
        for (count in listOf(2, 4, 6)) {
            val rules = rules(count)
            var s = rules.newGame(seed = 5L)
            val declarer = rules.currentActor(s)!!
            s = rules.apply(s, declarer, Action.PlaceBid(Bid.Misere))
            repeat(count - 1) { s = rules.apply(s, rules.currentActor(s)!!, Action.PlaceBid(Bid.Pass)) }
            s = rules.apply(s, declarer, Action.ExchangeKitty(s.hands[declarer]!!.take(KITTY_SIZE)))

            val expectedOut = teammatesOf(declarer, count)
            assertEquals(count - expectedOut.size, s.activeSeats.size, "at $count players")
            assertTrue(expectedOut.none { it in s.activeSeats }, "teammates must sit out at $count players")
            assertEquals(s.activeSeats.toSet(), s.tricksWon.keys)

            // A trick is played by exactly the active seats.
            repeat(s.activeSeats.size) {
                val seat = rules.currentActor(s)!!
                val card = (rules.legalActions(s, seat).first() as Action.PlayCard).card
                s = rules.apply(s, seat, Action.PlayCard(card))
            }
            assertEquals(count - expectedOut.size, s.lastTrick!!.plays.size)
            assertTrue(s.currentTrick.isEmpty())
        }
    }

    // --- Full hands ------------------------------------------------------------------------------

    /** Opener bids 6♠, everyone else passes; discard the first 3; play the first legal card. */
    private fun policy(view: PlayerView): Action = when (view.phase) {
        Phase.BIDDING -> Action.PlaceBid(if (view.highBid == null) Bid.Named(6, Trump.SPADES) else Bid.Pass)
        Phase.KITTY -> Action.ExchangeKitty(view.hand.take(KITTY_SIZE))
        Phase.PLAY -> Action.PlayCard(view.legalPlays.first())
        Phase.COMPLETE -> error("no action at COMPLETE")
    }

    private fun runOneHand(playerCount: Int, seed: Long): GameState {
        val rules = rules(playerCount)
        var s = rules.newGame(seed)
        while (!rules.isTerminal(s) && s.handNumber == 1) {
            val actor = rules.currentActor(s) ?: break
            val action = rules.legalActions(s, actor).firstOrNull()
                ?.takeIf { s.phase == Phase.PLAY } // during play: the first legal action each turn
                ?: policy(rules.view(s, actor))
            s = rules.apply(s, actor, action)
        }
        return s
    }

    @Test
    fun `a full hand plays ten tricks and scores at 2 and 6 players`() {
        for (count in listOf(2, 6)) {
            val s = runOneHand(count, seed = 42L)
            val result = s.lastHandResult
            assertNotNull(result, "at $count players")
            assertTrue(result.declarerTricks in 0..TRICKS_PER_HAND)
            assertEquals(2, s.handNumber, "the match advanced to the next deal at $count players")
            // Scores moved from zero by exactly the hand deltas, for both teams.
            assertEquals(result.teamDeltas[0], s.scores[0])
            assertEquals(result.teamDeltas[1], s.scores[1])
        }
    }

    @Test
    fun `tricks won sum to ten at every count`() {
        for (count in listOf(2, 4, 6)) {
            val rules = rules(count)
            var s = rules.newGame(seed = 42L)
            var finalTally: Map<Seat, Int>? = null
            while (!rules.isTerminal(s) && s.handNumber == 1) {
                val actor = rules.currentActor(s) ?: break
                if (s.phase == Phase.PLAY) {
                    // Invariant: the tally always equals the number of completed tricks.
                    assertEquals(s.trickNumber, s.tricksWon.values.sum(), "at $count players")
                }
                val action = rules.legalActions(s, actor).firstOrNull()
                    ?.takeIf { s.phase == Phase.PLAY }
                    ?: policy(rules.view(s, actor))
                // The engine redeals the moment the last card falls, so tally the final trick here.
                if (s.phase == Phase.PLAY &&
                    s.trickNumber == TRICKS_PER_HAND - 1 &&
                    s.currentTrick.size == s.activeSeats.size - 1
                ) {
                    val play = action as Action.PlayCard
                    val winner = TrickEvaluator(s.contract!!.trump).winner(s.currentTrick + TrickPlay(actor, play.card))
                    finalTally = s.tricksWon.toMutableMap().apply { put(winner, (get(winner) ?: 0) + 1) }
                }
                s = rules.apply(s, actor, action)
            }
            assertNotNull(finalTally, "the hand should have reached its final trick at $count players")
            assertEquals(TRICKS_PER_HAND, finalTally.values.sum(), "at $count players")
            assertNotNull(s.lastHandResult, "the completed hand should have been scored at $count players")
        }
    }

    @Test
    fun `a hand is deterministic for a given seed at 2 and 6 players`() {
        for (count in listOf(2, 6)) {
            assertEquals(runOneHand(count, 7L).scores, runOneHand(count, 7L).scores, "at $count players")
            assertEquals(runOneHand(count, 7L).lastHandResult, runOneHand(count, 7L).lastHandResult)
        }
    }

    @Test
    fun `the auction rotates through all seats and ends when all but the high bidder pass`() {
        for (count in listOf(2, 6)) {
            val rules = rules(count)
            var s = rules.newGame(seed = 9L, firstDealer = Seat(0))
            assertEquals(Seat(1), rules.currentActor(s), "the seat after the dealer opens at $count players")
            s = rules.apply(s, Seat(1), Action.PlaceBid(Bid.Named(6, Trump.SPADES)))
            // Everyone else passes, in clockwise order.
            var expected = Seat(2 % count)
            repeat(count - 1) {
                assertEquals(expected, rules.currentActor(s), "at $count players")
                s = rules.apply(s, expected, Action.PlaceBid(Bid.Pass))
                expected = nextSeat(expected, count)
                if (expected == Seat(1)) expected = nextSeat(expected, count) // skip the high bidder
            }
            assertEquals(Phase.KITTY, s.phase, "at $count players")
            assertEquals(Seat(1), s.contract?.declarer)
            assertEquals(HAND_SIZE + KITTY_SIZE, s.hands[Seat(1)]?.size)
        }
    }
}
