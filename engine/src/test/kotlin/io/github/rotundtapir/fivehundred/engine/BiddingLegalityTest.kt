// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for auction legality: once a bid is on the table, neither that bid nor anything
 * ranked at or below it may ever be offered or accepted again (user report: "I have the option to
 * bid 6♦ after that has already been bid").
 */
class BiddingLegalityTest {

    private val rules = FiveHundredRules()
    private val schedule = ScoreSchedule.Avondale

    @Test
    fun `a bid already made is never offered to later bidders`() {
        for (bid in schedule.ladder) {
            var state = rules.newGame(seed = 11)
            val opener = rules.currentActor(state)!!
            state = rules.apply(state, opener, Action.PlaceBid(bid))
            if (state.phase != Phase.BIDDING) continue // top bid may end the auction structure early

            val next = rules.currentActor(state)!!
            val offered = rules.view(state, next).legalBids

            assertFalse(bid in offered, "${bid.label} was re-offered after being bid")
            assertTrue(
                offered.all { it == Bid.Pass || schedule.outranks(it, bid) },
                "offers after ${bid.label} must strictly outrank it, got ${offered.map { it.label }}",
            )
        }
    }

    @Test
    fun `legalActions agrees with the view and excludes non-outranking bids`() {
        var state = rules.newGame(seed = 11)
        val opener = rules.currentActor(state)!!
        state = rules.apply(state, opener, Action.PlaceBid(Bid.Named(6, Trump.DIAMONDS)))

        val next = rules.currentActor(state)!!
        val actions = rules.legalActions(state, next)
            .filterIsInstance<Action.PlaceBid>().map { it.bid }.filter { it != Bid.Pass }

        assertFalse(Bid.Named(6, Trump.DIAMONDS) in actions)
        assertFalse(Bid.Named(6, Trump.CLUBS) in actions)
        assertFalse(Bid.Named(6, Trump.SPADES) in actions)
        assertTrue(Bid.Named(6, Trump.HEARTS) in actions)
        assertTrue(actions == rules.view(state, next).legalBids.filter { it != Bid.Pass })
    }

    @Test
    fun `applying an equal or lower bid is rejected`() {
        var state = rules.newGame(seed = 11)
        val opener = rules.currentActor(state)!!
        state = rules.apply(state, opener, Action.PlaceBid(Bid.Named(6, Trump.DIAMONDS)))
        val next = rules.currentActor(state)!!

        assertThrows<IllegalArgumentException> {
            rules.apply(state, next, Action.PlaceBid(Bid.Named(6, Trump.DIAMONDS)))
        }
        assertThrows<IllegalArgumentException> {
            rules.apply(state, next, Action.PlaceBid(Bid.Named(6, Trump.SPADES)))
        }
    }

    @Test
    fun `after an all-pass redeal the full ladder is legally available again`() {
        // Not a bug: if every seat passes, the hand is thrown in and redealt — earlier bids from the
        // abandoned auction do not constrain the new one.
        var state = rules.newGame(seed = 11)
        val opener = rules.currentActor(state)!!
        state = rules.apply(state, opener, Action.PlaceBid(Bid.Named(6, Trump.DIAMONDS)))
        // Remaining three pass -> 6♦ wins; instead pass ALL FOUR from a fresh game to force a redeal.
        var fresh = rules.newGame(seed = 11)
        repeat(PLAYERS) {
            val seat = rules.currentActor(fresh)!!
            fresh = rules.apply(fresh, seat, Action.PlaceBid(Bid.Pass))
        }
        assertTrue(fresh.phase == Phase.BIDDING && fresh.bidding.highBid == null, "expected a redeal")
        val offered = rules.view(fresh, rules.currentActor(fresh)!!).legalBids
        assertTrue(Bid.Named(6, Trump.DIAMONDS) in offered)
    }

    @Test
    fun `completed trick is recorded as lastTrick with its winner`() {
        var state = rules.newGame(seed = 11)
        // Drive bidding to a contract: opener bids 6♠, everyone else passes.
        state = rules.apply(state, rules.currentActor(state)!!, Action.PlaceBid(Bid.Named(6, Trump.SPADES)))
        repeat(3) { state = rules.apply(state, rules.currentActor(state)!!, Action.PlaceBid(Bid.Pass)) }
        // Kitty exchange: discard the first three cards.
        val declarer = rules.currentActor(state)!!
        state = rules.apply(state, declarer, Action.ExchangeKitty(state.hands[declarer]!!.take(3)))
        // Play one full trick with arbitrary legal cards.
        repeat(PLAYERS) {
            val seat = rules.currentActor(state)!!
            val card = (rules.legalActions(state, seat).first() as Action.PlayCard).card
            state = rules.apply(state, seat, Action.PlayCard(card))
        }
        val last = state.lastTrick
        assertTrue(last != null && last.plays.size == PLAYERS, "lastTrick should hold the completed trick")
        assertTrue(state.tricksWon[last!!.winner] == 1, "recorded winner should have won the trick")
        assertTrue(state.currentTrick.isEmpty())
    }
}
