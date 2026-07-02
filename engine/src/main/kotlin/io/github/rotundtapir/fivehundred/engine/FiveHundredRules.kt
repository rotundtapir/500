// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.deal
import io.github.rotundtapir.cardkit.core.shuffleWith
import kotlin.random.Random

/**
 * The rules of Australian 500 for 2, 4 or 6 players, as a pure state machine over [GameState].
 *
 * Implements cardkit's [GameRules] so it can be driven by a `GameDriver` with any mix of AI, human,
 * or (future) remote players. The whole match is deterministic given the initial seed.
 *
 * Per-count variations (everything else — bidding, kitty, scoring — is identical):
 *  - **4 players** (the standard game): 43-card deck; on a Misère the declarer's partner sits out.
 *  - **6 players**: 63-card deck (full 52 + the 11s and 12s + the red 13s + Joker), two teams of
 *    three; on a Misère both of the declarer's teammates sit out.
 *  - **2 players**: 43-card deck; each player is their own team; after dealing 10 each + the 3-card
 *    kitty, the remaining 20 cards are dead — dropped from the state entirely, never revealed or
 *    played. A Misère has nobody to sit out.
 */
class FiveHundredRules(
    private val schedule: ScoreSchedule = ScoreSchedule.Avondale,
    val playerCount: Int = 4,
) : GameRules<GameState, Action, PlayerView> {

    init {
        require(playerCount in setOf(2, 4, 6)) { "500 is played by 2, 4 or 6 players, not $playerCount" }
    }

    private val deck: List<Card> = fiveHundredDeck(playerCount)
    private val allSeats: List<Seat> = (0 until playerCount).map { Seat(it) }

    /** The next seat clockwise at this table. */
    private fun next(seat: Seat): Seat = nextSeat(seat, playerCount)

    // --- Setup -----------------------------------------------------------------------------------

    /** Starts a fresh match: deals the first hand and opens the auction. */
    fun newGame(seed: Long, firstDealer: Seat = Seat(0)): GameState =
        dealHand(seed = seed, dealer = firstDealer, handNumber = 1, scores = mapOf(0 to 0, 1 to 0), lastResult = null)

    private fun dealHand(
        seed: Long,
        dealer: Seat,
        handNumber: Int,
        scores: Map<Int, Int>,
        lastResult: HandResult?,
    ): GameState {
        val shuffled = deck.shuffleWith(Random(seed))
        val dealt = deal(shuffled, playerCount, HAND_SIZE)
        val hands = allSeats.associateWith { dealt.hands[it.index] }
        val opener = next(dealer)
        // At 2 players the deal leaves 23 cards: the first 3 are the kitty and the remaining 20 are
        // dead — deliberately dropped from the state so they can never be revealed or played. At 4
        // and 6 players the leftover is exactly the kitty.
        return GameState(
            rngSeed = seed,
            handNumber = handNumber,
            dealer = dealer,
            phase = Phase.BIDDING,
            hands = hands,
            kitty = dealt.leftover.take(KITTY_SIZE),
            bidding = BiddingState(toAct = opener),
            scores = scores,
            lastHandResult = lastResult,
        )
    }

    private fun nextSeed(seed: Long): Long = Random(seed).nextLong()

    // --- GameRules -------------------------------------------------------------------------------

    override fun currentActor(state: GameState): Seat? = when (state.phase) {
        Phase.BIDDING -> state.bidding.toAct
        Phase.KITTY -> state.contract?.declarer
        Phase.PLAY -> playerToAct(state)
        Phase.COMPLETE -> null
    }

    override fun isTerminal(state: GameState): Boolean = state.phase == Phase.COMPLETE

    override fun legalActions(state: GameState, seat: Seat): List<Action> {
        if (currentActor(state) != seat) return emptyList()
        return when (state.phase) {
            Phase.BIDDING -> buildList {
                add(Action.PlaceBid(Bid.Pass))
                schedule.ladder
                    .filter { state.bidding.highBid == null || schedule.outranks(it, state.bidding.highBid) }
                    .forEach { add(Action.PlaceBid(it)) }
            }
            Phase.KITTY -> emptyList() // discard is a constructed action; the view says how many
            Phase.PLAY -> legalPlaysFor(state, seat).map { Action.PlayCard(it) }
            Phase.COMPLETE -> emptyList()
        }
    }

    override fun view(state: GameState, seat: Seat): PlayerView {
        val toAct = currentActor(state)
        val isMyTurn = toAct == seat
        val legalBids = if (state.phase == Phase.BIDDING && isMyTurn) {
            buildList {
                add(Bid.Pass)
                schedule.ladder
                    .filter { state.bidding.highBid == null || schedule.outranks(it, state.bidding.highBid) }
                    .forEach { add(it) }
            }
        } else emptyList()
        val legalPlays = if (state.phase == Phase.PLAY && isMyTurn) legalPlaysFor(state, seat) else emptyList()
        val mustDiscard = if (state.phase == Phase.KITTY && state.contract?.declarer == seat) KITTY_SIZE else 0
        val exposed = state.contract?.let { c ->
            if (c.isOpen && state.phase == Phase.PLAY && seat != c.declarer) state.hands[c.declarer] else null
        }
        return PlayerView(
            seat = seat,
            phase = state.phase,
            playerCount = state.hands.size,
            handNumber = state.handNumber,
            hand = state.hands[seat].orEmpty(),
            handSizes = state.hands.mapValues { it.value.size },
            dealer = state.dealer,
            scores = state.scores,
            toAct = toAct,
            biddingHistory = state.bidding.history,
            highBid = state.bidding.highBid,
            highBidder = state.bidding.highBidder,
            legalBids = legalBids,
            contract = state.contract,
            trump = state.contract?.trump,
            leader = state.leader,
            currentTrick = state.currentTrick,
            ledSuit = state.ledSuit,
            lastTrick = state.lastTrick,
            tricksWon = state.tricksWon,
            trickNumber = state.trickNumber,
            legalPlays = legalPlays,
            mustDiscard = mustDiscard,
            exposedDeclarerHand = exposed,
            activeSeats = state.activeSeats,
            lastHandResult = state.lastHandResult,
            winner = state.winner,
        )
    }

    override fun apply(state: GameState, seat: Seat, action: Action): GameState {
        check(currentActor(state) == seat) { "It is not $seat's turn (phase=${state.phase})" }
        return when (state.phase) {
            Phase.BIDDING -> applyBid(state, seat, action as? Action.PlaceBid ?: illegal(action, "bid"))
            Phase.KITTY -> applyKitty(state, seat, action as? Action.ExchangeKitty ?: illegal(action, "kitty exchange"))
            Phase.PLAY -> applyPlay(state, seat, action as? Action.PlayCard ?: illegal(action, "card play"))
            Phase.COMPLETE -> error("Match is complete")
        }
    }

    // --- Bidding ---------------------------------------------------------------------------------

    private fun applyBid(state: GameState, seat: Seat, action: Action.PlaceBid): GameState {
        val b = state.bidding
        val bid = action.bid
        if (bid != Bid.Pass) {
            require(b.highBid == null || schedule.outranks(bid, b.highBid)) {
                "Bid ${bid.label} does not outrank ${b.highBid?.label}"
            }
        }
        val passed = if (bid == Bid.Pass) b.passed + seat else b.passed
        val highBid = if (bid == Bid.Pass) b.highBid else bid
        val highBidder = if (bid == Bid.Pass) b.highBidder else seat
        val history = b.history + (seat to bid)
        val active = allSeats.filter { it !in passed }

        return when {
            // Auction won: everyone but the high bidder has passed.
            highBid != null && active.size == 1 ->
                enterKitty(state.copy(bidding = b.copy(history = history, passed = passed, highBid = highBid, highBidder = highBidder)))
            // Passed out: nobody bid. Redeal with the next dealer.
            active.isEmpty() ->
                dealHand(nextSeed(state.rngSeed), next(state.dealer), state.handNumber + 1, state.scores, lastResult = null)
            // Continue the auction.
            else -> state.copy(
                bidding = b.copy(
                    history = history,
                    passed = passed,
                    highBid = highBid,
                    highBidder = highBidder,
                    toAct = nextActiveBidder(seat, passed),
                ),
            )
        }
    }

    private fun nextActiveBidder(from: Seat, passed: Set<Seat>): Seat {
        var s = next(from)
        while (s in passed) s = next(s)
        return s
    }

    /** Auction over: the declarer takes the kitty into hand and must discard down to 10. */
    private fun enterKitty(state: GameState): GameState {
        val b = state.bidding
        val declarer = b.highBidder!!
        val contract = Contract(declarer, b.highBid!!)
        val withKitty = state.hands.toMutableMap()
        withKitty[declarer] = state.hands[declarer].orEmpty() + state.kitty
        return state.copy(
            phase = Phase.KITTY,
            hands = withKitty,
            kitty = emptyList(),
            contract = contract,
        )
    }

    // --- Kitty exchange --------------------------------------------------------------------------

    private fun applyKitty(state: GameState, seat: Seat, action: Action.ExchangeKitty): GameState {
        val contract = state.contract!!
        val hand = state.hands[seat].orEmpty()
        require(action.discards.size == KITTY_SIZE) { "Must discard exactly $KITTY_SIZE cards" }
        require(action.discards.toSet().size == KITTY_SIZE) { "Discards must be distinct" }
        require(hand.containsAll(action.discards)) { "Can only discard cards from hand" }

        val newHand = hand.toMutableList().apply { action.discards.forEach { remove(it) } }
        val hands = state.hands.toMutableMap().apply { put(seat, newHand) }

        // Misère: the declarer's teammates (partner at 4 players, both teammates at 6, nobody at 2)
        // sit out for the hand.
        val sittingOut = if (contract.isMisere) teammatesOf(contract.declarer, playerCount) else emptyList()
        val active = allSeats.filter { it !in sittingOut }
        return state.copy(
            phase = Phase.PLAY,
            hands = hands,
            kitty = action.discards, // set aside (kept for the record)
            activeSeats = active,
            exposedHands = if (contract.isOpen) setOf(contract.declarer) else emptySet(),
            leader = contract.declarer,
            currentTrick = emptyList(),
            ledSuit = null,
            trickNumber = 0,
            tricksWon = active.associateWith { 0 },
        )
    }

    // --- Play ------------------------------------------------------------------------------------

    private fun playerToAct(state: GameState): Seat {
        val order = playOrder(state.leader!!, state.activeSeats)
        return order[state.currentTrick.size]
    }

    private fun playOrder(leader: Seat, active: List<Seat>): List<Seat> {
        val ordered = active.sortedBy { it.index }
        val start = ordered.indexOf(leader)
        return List(ordered.size) { ordered[(start + it) % ordered.size] }
    }

    private fun legalPlaysFor(state: GameState, seat: Seat): List<Card> {
        val hand = state.hands[seat].orEmpty()
        if (state.currentTrick.isEmpty()) return hand // leading: play anything
        return TrickEvaluator(state.contract!!.trump).legalFollows(hand, state.ledSuit)
    }

    private fun applyPlay(state: GameState, seat: Seat, action: Action.PlayCard): GameState {
        val legal = legalPlaysFor(state, seat)
        require(action.card in legal) { "Illegal play ${action.card.code}" }
        val eval = TrickEvaluator(state.contract!!.trump)

        val hand = state.hands[seat].orEmpty().toMutableList().apply { remove(action.card) }
        val hands = state.hands.toMutableMap().apply { put(seat, hand) }
        val play = TrickPlay(seat, action.card, action.nominate)
        val isLead = state.currentTrick.isEmpty()
        val trick = state.currentTrick + play
        val ledSuit = if (isLead) eval.ledSuitOf(play) else state.ledSuit

        if (trick.size < state.activeSeats.size) {
            return state.copy(hands = hands, currentTrick = trick, ledSuit = ledSuit)
        }

        // Trick complete.
        val winner = eval.winner(trick)
        val tricksWon = state.tricksWon.toMutableMap().apply { put(winner, (get(winner) ?: 0) + 1) }
        val trickNumber = state.trickNumber + 1

        val completed = CompletedTrick(trick, winner)
        if (trickNumber < TRICKS_PER_HAND) {
            return state.copy(
                hands = hands,
                tricksWon = tricksWon,
                trickNumber = trickNumber,
                leader = winner,
                currentTrick = emptyList(),
                ledSuit = null,
                lastTrick = completed,
            )
        }

        // Hand complete: score it.
        return completeHand(
            state.copy(hands = hands, tricksWon = tricksWon, trickNumber = trickNumber, lastTrick = completed),
        )
    }

    private fun completeHand(state: GameState): GameState {
        val contract = state.contract!!
        val result = scoreHand(contract, state.tricksWon, schedule)
        val newScores = state.scores.mapValues { (team, s) -> s + (result.teamDeltas[team] ?: 0) }
        val matchWinner = determineWinner(newScores, result)

        return if (matchWinner != null) {
            state.copy(phase = Phase.COMPLETE, scores = newScores, lastHandResult = result, winner = matchWinner)
        } else {
            dealHand(nextSeed(state.rngSeed), next(state.dealer), state.handNumber + 1, newScores, result)
        }
    }

    private fun illegal(action: Action, expected: String): Nothing =
        throw IllegalArgumentException("Expected a $expected action but got $action")
}
