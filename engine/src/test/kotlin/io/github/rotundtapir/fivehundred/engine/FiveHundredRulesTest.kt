// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Seat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FiveHundredRulesTest {
    private val rules = FiveHundredRules()

    /** A deterministic policy: the opener bids 6♠, everyone else passes; discard the first 3; play the first legal card. */
    private fun policy(view: PlayerView): Action = when (view.phase) {
        Phase.BIDDING -> Action.PlaceBid(if (view.highBid == null) Bid.Named(6, Trump.SPADES) else Bid.Pass)
        Phase.KITTY -> Action.ExchangeKitty(view.hand.take(KITTY_SIZE))
        Phase.PLAY -> Action.PlayCard(view.legalPlays.first())
        Phase.COMPLETE -> error("no action at COMPLETE")
    }

    private fun runOneHand(seed: Long): GameState {
        var s = rules.newGame(seed, Seat(0))
        while (!rules.isTerminal(s) && s.handNumber == 1) {
            val actor = rules.currentActor(s) ?: break
            s = rules.apply(s, actor, policy(rules.view(s, actor)))
        }
        return s
    }

    @Test
    fun `auction resolves, declarer takes kitty to 13 then discards to 10 and play begins`() {
        var s = rules.newGame(seed = 1L, firstDealer = Seat(0))
        // Opener is seat 1; they bid 6 spades, the rest pass.
        assertEquals(Seat(1), rules.currentActor(s))
        s = rules.apply(s, Seat(1), Action.PlaceBid(Bid.Named(6, Trump.SPADES)))
        s = rules.apply(s, Seat(2), Action.PlaceBid(Bid.Pass))
        s = rules.apply(s, Seat(3), Action.PlaceBid(Bid.Pass))
        s = rules.apply(s, Seat(0), Action.PlaceBid(Bid.Pass))

        assertEquals(Phase.KITTY, s.phase)
        assertEquals(Seat(1), s.contract?.declarer)
        assertEquals(13, s.hands[Seat(1)]?.size) // 10 + 3-card kitty
        assertEquals(Seat(1), rules.currentActor(s))

        val discards = s.hands[Seat(1)]!!.take(3)
        s = rules.apply(s, Seat(1), Action.ExchangeKitty(discards))

        assertEquals(Phase.PLAY, s.phase)
        s.hands.values.forEach { assertEquals(10, it.size) }
        assertEquals(listOf(Seat(0), Seat(1), Seat(2), Seat(3)), s.activeSeats)
        assertEquals(Seat(1), s.leader) // declarer leads first trick
    }

    @Test
    fun `misere seats out the declarer's partner`() {
        var s = rules.newGame(seed = 5L, firstDealer = Seat(0))
        s = rules.apply(s, Seat(1), Action.PlaceBid(Bid.Misere))
        s = rules.apply(s, Seat(2), Action.PlaceBid(Bid.Pass))
        s = rules.apply(s, Seat(3), Action.PlaceBid(Bid.Pass))
        s = rules.apply(s, Seat(0), Action.PlaceBid(Bid.Pass))
        s = rules.apply(s, Seat(1), Action.ExchangeKitty(s.hands[Seat(1)]!!.take(3)))

        // Declarer seat 1's partner is seat 3, who sits out.
        assertEquals(listOf(Seat(0), Seat(1), Seat(2)), s.activeSeats)
        assertEquals(setOf(Seat(0), Seat(1), Seat(2)), s.tricksWon.keys)
    }

    @Test
    fun `a full hand plays out ten tricks and scores`() {
        val s = runOneHand(seed = 42L)
        val result = s.lastHandResult
        assertNotNull(result)
        assertTrue(result.declarerTricks in 0..TRICKS_PER_HAND)
        // The match advanced to the next deal with scores updated from zero by exactly the hand deltas.
        assertEquals(2, s.handNumber)
        assertEquals(result.teamDeltas[0], s.scores[0])
        assertEquals(result.teamDeltas[1], s.scores[1])
    }

    @Test
    fun `the match is deterministic for a given seed`() {
        assertEquals(runOneHand(7L).scores, runOneHand(7L).scores)
        assertEquals(runOneHand(7L).lastHandResult, runOneHand(7L).lastHandResult)
    }

    @Test
    fun `GameDriver drives a full hand with suspend players`() = runTest {
        // Wrap the rules so the match ends after one hand, keeping the test bounded.
        val oneHand = object : GameRules<GameState, Action, PlayerView> by rules {
            override fun isTerminal(state: GameState): Boolean = rules.isTerminal(state) || state.handNumber > 1
        }
        val player = Player<PlayerView, Action> { view -> policy(view) }
        val players = (0 until rules.playerCount).associate { Seat(it) to player }

        val terminal = GameDriver(oneHand, players).play(rules.newGame(seed = 99L, firstDealer = Seat(0)))

        assertNotNull(terminal.lastHandResult)
        assertTrue(terminal.handNumber > 1 || terminal.phase == Phase.COMPLETE)
    }
}
