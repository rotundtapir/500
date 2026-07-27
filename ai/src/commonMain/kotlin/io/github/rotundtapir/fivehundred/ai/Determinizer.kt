// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.ai.ConstrainedHandSampler
import io.github.rotundtapir.cardkit.ai.TrickMemory
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.BiddingState
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.Trump
import io.github.rotundtapir.fivehundred.engine.fiveHundredDeck
import kotlin.random.Random

/**
 * Accumulates what one bot seat has observed across a hand, beyond what a single [PlayerView]
 * carries. The view only exposes the current and the most recent trick, so cards from older tricks
 * would otherwise be forgotten and could be dealt back to opponents during determinization.
 *
 * The card memory itself is cardkit-ai's [TrickMemory]; this wrapper adds 500's observation
 * plumbing — the hand-boundary reset and this seat's own kitty discards.
 *
 * One instance per bot seat per game: an *active* seat plays in every trick, so between consecutive
 * [observe] calls at most one trick completes and `lastTrick` + `currentTrick` cover every play
 * since the previous call. If observation ever misses a play the sampled worlds are merely less
 * informed, never inconsistent — missed cards stay in the unknown pool.
 */
internal class SeenTracker {
    private var handNumber = -1
    private val memory = TrickMemory()

    /** Every card observed hitting the felt this hand. */
    val seenPlays: Set<Card> get() = memory.seenPlays

    /** Effective suits each seat has been proven void in (failed to follow). */
    val voids: Map<Seat, Set<Suit>> get() = memory.voids

    /** The cards this seat itself buried after taking the kitty — hidden from everyone else. */
    var myDiscards: List<Card> = emptyList()
        private set

    /** Call at the top of every decision with the view being decided on. */
    fun observe(view: PlayerView) {
        if (view.handNumber != handNumber) {
            handNumber = view.handNumber
            memory.reset()
            myDiscards = emptyList()
        }
        val eval = TrickEvaluator(view.trump ?: return) // nothing on the felt before a contract
        view.lastTrick?.let { memory.record(it.plays, eval) }
        memory.record(view.currentTrick, eval)
    }

    /** Call when this seat's kitty exchange is decided, so its discards never get re-dealt. */
    fun recordMyDiscards(cards: List<Card>) {
        myDiscards = cards
    }
}

/**
 * Samples full [GameState]s consistent with a [PlayerView] plus a [SeenTracker]'s observations, for
 * Monte-Carlo evaluation: hidden cards are dealt randomly to the other seats (respecting proven
 * voids where possible) by cardkit-ai's [ConstrainedHandSampler], and the public state is copied
 * across verbatim.
 *
 * Cards nobody can see — the kitty another declarer buried, the twenty dead cards at 2 players —
 * simply stay unassigned; [io.github.rotundtapir.fivehundred.engine.FiveHundredRules.apply] never
 * checks card conservation, so such worlds replay perfectly legally.
 */
internal class Determinizer(playerCount: Int) {

    private val sampler = ConstrainedHandSampler(fiveHundredDeck(playerCount))

    /** The full deck for this table size — also the universe for "which cards are still unseen". */
    val deck: List<Card> get() = sampler.deck

    /** One sampled world: a [GameState] the reducer accepts, agreeing with everything [view] shows. */
    fun sample(view: PlayerView, tracker: SeenTracker, random: Random): GameState {
        val result = sampler.sample(
            fixedHands = fixedHands(view),
            handSizes = view.handSizes,
            knownGone = tracker.seenPlays + tracker.myDiscards,
            voids = tracker.voids,
            // Before PLAY no void can have been proven, so skip the repair pass entirely.
            eval = if (view.phase == Phase.PLAY) TrickEvaluator(view.trump ?: Trump.NO_TRUMP) else null,
            random = random,
        )
        // During BIDDING the kitty is still face down and must exist — the auction winner takes it
        // into hand. In later phases the reducer never reads it, so leave it empty.
        val kitty = if (view.phase == Phase.BIDDING) List(KITTY_SIZE) { result.pool.removeFirst() } else emptyList()
        return reconstruct(view, result.hands, kitty, random)
    }

    /** Hands whose cards are certain and must not be resampled: mine, and an exposed declarer's. */
    private fun fixedHands(view: PlayerView): Map<Seat, List<Card>> = buildMap {
        put(view.seat, view.hand)
        val exposed = view.exposedDeclarerHand
        val declarer = view.contract?.declarer
        if (exposed != null && declarer != null) put(declarer, exposed)
    }

    /** Copies the public state across verbatim around the sampled [hands] and [kitty]. */
    private fun reconstruct(
        view: PlayerView,
        hands: Map<Seat, List<Card>>,
        kitty: List<Card>,
        random: Random,
    ): GameState = GameState(
        // Only consumed if a rollout deals the next hand; drawn from the injected Random so
        // sampled worlds stay reproducible under a fixed seed.
        rngSeed = random.nextLong(),
        handNumber = view.handNumber,
        dealer = view.dealer,
        phase = view.phase,
        teamCount = view.teamCount,
        hands = hands,
        kitty = kitty,
        bidding = BiddingState(
            history = view.biddingHistory,
            // Matches applyBid's accumulation: a seat is out of the auction iff it has passed.
            passed = view.biddingHistory.filter { it.second == Bid.Pass }.map { it.first }.toSet(),
            highBid = view.highBid,
            highBidder = view.highBidder,
            toAct = view.toAct ?: view.seat,
        ),
        contract = view.contract,
        activeSeats = view.activeSeats,
        leader = view.leader,
        currentTrick = view.currentTrick,
        ledSuit = view.ledSuit,
        trickNumber = view.trickNumber,
        tricksWon = view.tricksWon,
        lastTrick = view.lastTrick,
        scores = view.scores,
        lastHandResult = view.lastHandResult,
        handResults = view.handResults,
        winner = view.winner,
    )
}
