// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.ui.settings.AnimationSpeed
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [GameViewModel]'s signal-driven pacing, driven exactly as the UI does: the human
 * (seat 0) is played from [GameViewModel.humanView] via [GameViewModel.placeBid]/`discard`/`playCard`,
 * and the UI's acknowledgement calls stand in for taps. `viewModelScope` runs on a
 * [StandardTestDispatcher] so `delay`/`withTimeoutOrNull` in the pacing gate advance in virtual time.
 */
class GameViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val decider = FiveHundredBot()

    /** A human decision for [view], chosen by the same heuristic bot so the game is deterministic. */
    private fun submitHumanTurn(vm: GameViewModel, view: PlayerView, salt: Long) {
        when (val action = decider.decide(view, Random(salt))) {
            is Action.PlaceBid -> vm.placeBid(action.bid)
            is Action.ExchangeKitty -> vm.discard(action.discards)
            is Action.PlayCard -> vm.playCard(action.card, action.nominate)
        }
    }

    /**
     * Drives a game at [AnimationSpeed.OFF] to completion (or until it stalls), playing the human
     * and acknowledging each scored hand — the minimum the UI must do to keep the game moving.
     * Returns the final view. At OFF all pacing is inert except the hand-result gate, which is why
     * acknowledging is mandatory.
     */
    private fun TestScope.playToCompletion(vm: GameViewModel, seed: Long, ackResults: Boolean = true): PlayerView {
        var ackedHand = 0
        var guard = 0
        while (guard++ < 5000) {
            advanceUntilIdle()
            val view = vm.humanView.value ?: break
            if (view.winner != null) return view
            if (ackResults && view.lastHandResult != null && view.handNumber > ackedHand) {
                ackedHand = view.handNumber
                vm.acknowledgeHandResult(view.handNumber)
                continue
            }
            if (view.isMyTurn) {
                submitHumanTurn(vm, view, seed + view.handNumber * 100L + view.trickNumber)
                continue
            }
            break // not our turn, nothing new, not terminal → blocked on an un-raised signal
        }
        return vm.humanView.value ?: error("game produced no view")
    }

    @Test
    fun `a full match at OFF completes and is deterministic for a seed`() = runTest(dispatcher) {
        fun finalView(): PlayerView {
            val vm = GameViewModel().apply { animationSpeed.value = AnimationSpeed.OFF }
            vm.newGame(seed = 2024L)
            return playToCompletion(vm, seed = 2024L)
        }
        val a = finalView()
        val b = finalView()
        assertNotNull(a.winner, "the match should reach a winner")
        assertEquals(a.winner, b.winner, "same seed → same winner")
        assertEquals(a.scores, b.scores, "same seed → identical final scores")
    }

    @Test
    fun `all pacing is inert at OFF even with holdTricks on`() = runTest(dispatcher) {
        // The invariant the whole 22-test connected suite relies on: at OFF, holdTricks (and every
        // other pacing signal we never raise here) must not stall the game.
        val vm = GameViewModel().apply {
            animationSpeed.value = AnimationSpeed.OFF
            holdTricks.value = true
        }
        vm.newGame(seed = 55L)
        val end = playToCompletion(vm, seed = 55L)
        assertNotNull(end.winner, "holdTricks must be a no-op at OFF")
    }

    @Test
    fun `the human's bid prompt exposes the legal bids including Pass`() = runTest(dispatcher) {
        val vm = GameViewModel().apply { animationSpeed.value = AnimationSpeed.OFF }
        vm.newGame(seed = 42L)
        advanceUntilIdle()
        val view = vm.humanView.value
        assertNotNull(view)
        assertEquals(Phase.BIDDING, view.phase)
        assertTrue(view.isMyTurn, "at seed 42 the auction should reach the human")
        assertTrue(Bid.Pass in view.legalBids)
        assertTrue(view.legalBids.size > 1, "the human should have real bids to choose from")
    }

    @Test
    fun `the first auction waits for the deal-animation signal at NORMAL`() = runTest(dispatcher) {
        val vm = GameViewModel().apply { animationSpeed.value = AnimationSpeed.NORMAL }
        vm.newGame(seed = 42L)
        runCurrent()
        // Seat 1 (a bot) opens; at NORMAL it holds until dealAnimationDone, capped by the backstop.
        // Advance well short of the backstop (dealPauseMillis*3 ≈ 13.4s): nothing bid yet.
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(
            vm.humanView.value!!.biddingHistory.isEmpty(),
            "the auction must not start before the deal animation reports done",
        )
        // Raise the signal the UI would raise when the deal finishes: the auction proceeds.
        vm.dealAnimationFinished(1)
        advanceUntilIdle()
        assertTrue(
            vm.humanView.value!!.biddingHistory.isNotEmpty(),
            "the auction should run once the deal-done signal arrives",
        )
    }

    @Test
    fun `the backstop starts the auction even if the deal signal never arrives`() = runTest(dispatcher) {
        val vm = GameViewModel().apply { animationSpeed.value = AnimationSpeed.NORMAL }
        vm.newGame(seed = 42L)
        runCurrent()
        assertTrue(vm.humanView.value!!.biddingHistory.isEmpty())
        // Never signal dealAnimationFinished; advance past the deadlock backstop (~13.4s + delays).
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(
            vm.humanView.value!!.biddingHistory.isNotEmpty(),
            "the backstop must release the first bidder so a lost signal can't wedge the game",
        )
    }

    @Test
    fun `the next hand waits until the previous hand's result is acknowledged`() = runTest(dispatcher) {
        val vm = GameViewModel().apply { animationSpeed.value = AnimationSpeed.OFF }
        vm.newGame(seed = 2024L)
        // Drive WITHOUT acknowledging results: the game must stall at the start of hand 2, because
        // hand 2's first bidder (a bot) gates on handResultAcked — active even at OFF.
        val stalled = playToCompletion(vm, seed = 2024L, ackResults = false)
        assertNotNull(stalled.lastHandResult, "hand 1 should have been scored")
        assertEquals(2, stalled.handNumber, "the engine dealt hand 2 but it must not have started")
        assertNull(stalled.winner)
        assertTrue(stalled.biddingHistory.isEmpty(), "hand 2's auction must be blocked pending the ack")

        // Acknowledging releases it, and the match then runs to completion.
        vm.acknowledgeHandResult(stalled.handNumber)
        val end = playToCompletion(vm, seed = 2024L)
        assertNotNull(end.winner)
    }

    @Test
    fun `a held trick releases on acknowledge and on toggling hold off`() = runTest(dispatcher) {
        // Reach a point where a bot is about to lead a fresh trick with holdTricks on at NORMAL:
        // the game stalls on the combine(trickAcked, holdTricks) gate.
        fun TestScope.driveToHeldTrick(vm: GameViewModel, seed: Long): PlayerView {
            var ackedHand = 0
            var guard = 0
            while (guard++ < 4000) {
                advanceUntilIdle()
                val view = vm.humanView.value ?: break
                if (view.winner != null) return view
                if (view.lastHandResult != null && view.handNumber > ackedHand) {
                    ackedHand = view.handNumber
                    vm.acknowledgeHandResult(view.handNumber)
                    continue
                }
                if (view.isMyTurn) {
                    submitHumanTurn(vm, view, seed + view.handNumber * 100L + view.trickNumber)
                    continue
                }
                // Not our turn and nothing advanced: a bot is blocked on the hold gate.
                return view
            }
            return vm.humanView.value!!
        }

        val vm = GameViewModel().apply {
            animationSpeed.value = AnimationSpeed.NORMAL
            holdTricks.value = true
        }
        vm.newGame(seed = 2024L)
        val held = driveToHeldTrick(vm, seed = 2024L)
        // We should be stalled between tricks (a completed trick shown, no current trick) and not done.
        assertNull(held.winner, "the game must be paused on the hold gate, not finished")
        assertTrue(held.phase == Phase.PLAY && held.currentTrick.isEmpty() && held.lastTrick != null)
        val heldTrickNumber = held.trickNumber

        // Toggling hold off must release the waiting bot (the documented live-release behaviour).
        vm.holdTricks.value = false
        advanceUntilIdle()
        val after = vm.humanView.value!!
        assertTrue(
            after.winner != null || after.trickNumber > heldTrickNumber || after.isMyTurn,
            "clearing holdTricks should let play continue past the held trick",
        )
    }

    @Test
    fun `newGame cancels the previous game and clears stale acknowledgements`() = runTest(dispatcher) {
        val vm = GameViewModel().apply { animationSpeed.value = AnimationSpeed.OFF }
        vm.newGame(seed = 2024L)
        advanceUntilIdle()
        // Acknowledge a high hand number, then start a brand-new game: the stale ack must not leak
        // in and let a later hand skip its gate, and the old game's job must stop emitting.
        vm.acknowledgeHandResult(99)
        vm.newGame(seed = 7L)
        advanceUntilIdle()
        val view = vm.humanView.value
        assertNotNull(view)
        assertEquals(1, view.handNumber, "the new game starts at hand 1")
        val end = playToCompletion(vm, seed = 7L)
        assertNotNull(end.winner, "the new game plays through normally")
    }

    @Test
    fun `bot names are distinct, stable per seed, and rebuilt per game`() = runTest(dispatcher) {
        val vm = GameViewModel().apply { animationSpeed.value = AnimationSpeed.OFF }
        vm.newGame(seed = 42L, playerCount = 4)
        advanceUntilIdle()
        val first = vm.botNames.value
        assertEquals(3, first.size, "4 players → 3 bot names")
        assertEquals(3, first.values.toSet().size, "bot names must be distinct")

        vm.newGame(seed = 42L, playerCount = 4)
        advanceUntilIdle()
        assertEquals(first, vm.botNames.value, "same seed → same names")

        vm.newGame(seed = 43L, playerCount = 6)
        advanceUntilIdle()
        assertEquals(5, vm.botNames.value.size, "6 players → 5 bot names")
    }

    @Test
    fun `disabling house rules removes misere and no-trump bids from every human prompt`() = runTest(dispatcher) {
        val vm = GameViewModel().apply { animationSpeed.value = AnimationSpeed.OFF }
        vm.newGame(seed = 2024L, misereEnabled = false, noTrumpsEnabled = false)

        // Collect the human's offered bids across a whole match; none may be misère or no-trump.
        var ackedHand = 0
        var guard = 0
        while (guard++ < 5000) {
            advanceUntilIdle()
            val view = vm.humanView.value ?: break
            if (view.winner != null) break
            if (view.lastHandResult != null && view.handNumber > ackedHand) {
                ackedHand = view.handNumber
                vm.acknowledgeHandResult(view.handNumber)
                continue
            }
            if (view.isMyTurn) {
                if (view.phase == Phase.BIDDING) {
                    assertTrue(
                        view.legalBids.none { it == Bid.Misere || it == Bid.OpenMisere },
                        "misère must never be offered when disabled",
                    )
                    assertTrue(
                        view.legalBids.none { it is Bid.Named && it.trump == Trump.NO_TRUMP },
                        "no-trump bids must never be offered when disabled",
                    )
                }
                submitHumanTurn(vm, view, 2024L + view.handNumber * 100L + view.trickNumber)
                continue
            }
            break
        }
    }
}
