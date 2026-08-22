// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.of
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.CompletedTrick
import io.github.rotundtapir.fivehundred.engine.Contract
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeterminizerTest {
    private val rules = FiveHundredRules()
    private val heuristic = FiveHundredBot()

    /**
     * Drives a real game with the heuristic bot until [ready], keeping [target]'s tracker fed the
     * way AdvancedBot would (observe on every decision the target makes). Returns the state (with
     * it being [target]'s turn) and the tracker.
     */
    private fun driveTo(
        seed: Long,
        target: Seat,
        ready: (GameState) -> Boolean,
    ): Pair<GameState, SeenTracker> {
        val tracker = SeenTracker()
        val random = Random(0)
        var s = rules.newGame(seed)
        while (!(ready(s) && rules.currentActor(s) == target)) {
            check(s.phase != Phase.COMPLETE) { "match ended before reaching the target state" }
            val actor = rules.currentActor(s)!!
            val view = rules.view(s, actor)
            if (actor == target) tracker.observe(view)
            s = rules.apply(s, actor, heuristic.decide(view, random))
        }
        tracker.observe(rules.view(s, target))
        return s to tracker
    }

    /** Rolls [world] to the end of its hand with the heuristic; throws on any illegal move. */
    private fun rolloutHand(world: GameState) {
        val startHand = world.handNumber
        val random = Random(1)
        var s = world
        while (s.phase != Phase.COMPLETE && s.handNumber == startHand) {
            val actor = rules.currentActor(s)!!
            s = rules.apply(s, actor, heuristic.decide(rules.view(s, actor), random))
        }
    }

    private fun assertConsistent(world: GameState, view: PlayerView, tracker: SeenTracker) {
        assertEquals(view.hand, world.hands.getValue(view.seat), "own hand must be copied verbatim")
        assertEquals(
            view.handSizes,
            world.hands.mapValues { it.value.size },
            "sampled hand sizes must match the view",
        )
        val dealt = world.hands.values.flatten() + world.kitty
        assertEquals(dealt.size, dealt.toSet().size, "no card may be dealt twice")
        val replayed = dealt.filter { it in tracker.seenPlays }
        assertTrue(replayed.isEmpty(), "cards already played must stay off the table: $replayed")
    }

    @Test
    fun `bidding sample reconstructs a playable world with a kitty`() {
        val target = Seat(1)
        val (state, tracker) = driveTo(seed = 11L, target = target) { it.phase == Phase.BIDDING }
        val view = rules.view(state, target)
        val determinizer = Determinizer(rules.playerCount)
        repeat(5) { i ->
            val world = determinizer.sample(view, determinizer.prepare(view, tracker), Random(i.toLong()))
            assertConsistent(world, view, tracker)
            assertEquals(3, world.kitty.size, "a bidding-phase world needs a face-down kitty")
            rolloutHand(world)
        }
    }

    @Test
    fun `mid-play sample reconstructs a playable world`() {
        val target = Seat(2)
        val (state, tracker) = driveTo(seed = 42L, target = target) {
            it.phase == Phase.PLAY && it.trickNumber >= 4
        }
        val view = rules.view(state, target)
        val determinizer = Determinizer(rules.playerCount)
        repeat(5) { i ->
            val world = determinizer.sample(view, determinizer.prepare(view, tracker), Random(100L + i))
            assertConsistent(world, view, tracker)
            rolloutHand(world)
        }
    }

    @Test
    fun `kitty sample reconstructs a playable world for the declarer`() {
        // Find a seed whose auction someone wins (no pass-out), then sample at the KITTY phase.
        val seed = generateSequence(1L) { it + 1 }.first { candidate ->
            var s = rules.newGame(candidate)
            val random = Random(0)
            var steps = 0
            while (s.phase == Phase.BIDDING && steps < 20) {
                val actor = rules.currentActor(s)!!
                s = rules.apply(s, actor, heuristic.decide(rules.view(s, actor), random))
                steps++
            }
            s.phase == Phase.KITTY
        }
        var s = rules.newGame(seed)
        val random = Random(0)
        while (s.phase == Phase.BIDDING) {
            val actor = rules.currentActor(s)!!
            s = rules.apply(s, actor, heuristic.decide(rules.view(s, actor), random))
        }
        val declarer = s.contract!!.declarer
        val tracker = SeenTracker()
        val view = rules.view(s, declarer)
        tracker.observe(view)
        val det = Determinizer(rules.playerCount)
        val world = det.sample(view, det.prepare(view, tracker), Random(7))
        assertConsistent(world, view, tracker)
        rolloutHand(world)
    }

    @Test
    fun `proven voids are respected when sampling`() {
        // Seat 2 discarded a heart on a spade lead — proven void in spades (the effective suit,
        // which includes the left bower J♣ and the Joker).
        val lastTrick = CompletedTrick(
            plays = listOf(
                TrickPlay(Seat(0), Rank.ACE of Suit.SPADES),
                TrickPlay(Seat(1), Rank.FIVE of Suit.SPADES),
                TrickPlay(Seat(2), Rank.FIVE of Suit.HEARTS),
                TrickPlay(Seat(3), Rank.SIX of Suit.SPADES),
            ),
            winner = Seat(0),
        )
        val myHand = listOf(
            Rank.KING of Suit.SPADES, Rank.QUEEN of Suit.SPADES, Rank.TEN of Suit.SPADES,
            Rank.ACE of Suit.HEARTS, Rank.KING of Suit.HEARTS,
            Rank.ACE of Suit.DIAMONDS, Rank.KING of Suit.DIAMONDS,
            Rank.ACE of Suit.CLUBS, Rank.KING of Suit.CLUBS,
        )
        val contract = Contract(Seat(0), Bid.Named(7, Trump.SPADES))
        val view = PlayerView(
            seat = Seat(0),
            phase = Phase.PLAY,
            playerCount = 4,
            teamCount = 2,
            handNumber = 1,
            hand = myHand,
            handSizes = mapOf(Seat(0) to 9, Seat(1) to 9, Seat(2) to 9, Seat(3) to 9),
            dealer = Seat(3),
            scores = mapOf(0 to 0, 1 to 0),
            toAct = Seat(0),
            biddingHistory = emptyList(),
            highBid = contract.bid,
            highBidder = Seat(0),
            legalBids = emptyList(),
            contract = contract,
            trump = contract.trump,
            leader = Seat(0),
            currentTrick = emptyList(),
            ledSuit = null,
            lastTrick = lastTrick,
            tricksWon = mapOf(Seat(0) to 1, Seat(1) to 0, Seat(2) to 0, Seat(3) to 0),
            trickNumber = 1,
            legalPlays = myHand,
            mustDiscard = 0,
            exposedDeclarerHand = null,
            activeSeats = listOf(Seat(0), Seat(1), Seat(2), Seat(3)),
            lastHandResult = null,
            winner = null,
        )
        val tracker = SeenTracker()
        tracker.observe(view)
        assertEquals<Set<Suit>?>(setOf(Suit.SPADES), tracker.voids[Seat(2)])

        val determinizer = Determinizer(4)
        val eval = TrickEvaluator(Trump.SPADES)
        repeat(20) { i ->
            val world = determinizer.sample(view, determinizer.prepare(view, tracker), Random(i.toLong()))
            val offending = world.hands.getValue(Seat(2)).filter { eval.effectiveSuit(it) == Suit.SPADES }
            assertTrue(offending.isEmpty(), "seat 2 is void in spades but was dealt $offending")
        }
    }

    @Test
    fun `tracker resets between hands and remembers own discards`() {
        val tracker = SeenTracker()
        val discards: List<Card> = listOf(Rank.FOUR of Suit.HEARTS, Rank.FIVE of Suit.HEARTS, Rank.SIX of Suit.HEARTS)
        val (state, _) = driveTo(seed = 42L, target = Seat(2)) { it.phase == Phase.PLAY && it.trickNumber >= 2 }
        val view = rules.view(state, Seat(2))
        tracker.observe(view)
        tracker.recordMyDiscards(discards)
        assertTrue(tracker.seenPlays.isNotEmpty(), "plays from the felt should be tracked")
        assertEquals(discards, tracker.myDiscards)

        // A view from a later hand wipes everything.
        tracker.observe(view.copy(handNumber = view.handNumber + 1, currentTrick = emptyList(), lastTrick = null))
        assertTrue(tracker.seenPlays.isEmpty(), "a new hand must reset the seen set")
        assertTrue(tracker.myDiscards.isEmpty(), "a new hand must reset the recorded discards")
    }
}
