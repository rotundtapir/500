// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.ChannelPlayer
import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.StrategyPlayer
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Drives a game of 500 for the human at seat 0 against `playerCount - 1` [FiveHundredBot] opponents.
 *
 * The engine's [GameDriver] runs in [viewModelScope]; every state transition is pushed to [humanView]
 * (the redacted, seat-0 projection) so the UI can render. Human decisions are fed back through a
 * [ChannelPlayer] — the same seam a remote opponent would use.
 */
class GameViewModel : ViewModel() {
    // Rebuilt per game so the table size can change between games; only read via [humanView].
    private var rules = FiveHundredRules()
    private val bot = FiveHundredBot()
    private val humanSeat = Seat(0)
    private val human = ChannelPlayer<PlayerView, Action>()

    private val state = MutableStateFlow<GameState?>(null)

    /** How quickly bot turns play out — set by the activity from the persisted setting, read live. */
    val animationSpeed = MutableStateFlow(AnimationSpeed.NORMAL)

    /** Highest hand number whose end-of-hand result dialog the player has dismissed. */
    private val handResultAcked = MutableStateFlow(0)

    /** Called by the UI when the hand-result dialog is dismissed; unblocks the next hand. */
    fun acknowledgeHandResult(handNumber: Int) {
        handResultAcked.value = maxOf(handResultAcked.value, handNumber)
    }

    /** Highest hand number whose shuffle/deal animation has finished on screen. */
    private val dealAnimationDone = MutableStateFlow(0)

    /** Called by the UI when a hand's deal animation completes; releases the first bidder. */
    fun dealAnimationFinished(handNumber: Int) {
        dealAnimationDone.value = maxOf(dealAnimationDone.value, handNumber)
    }

    /** Whether completed tricks stay on the felt until tapped away — set live from the UI toggle. */
    val holdTricks = MutableStateFlow(false)

    /** Timed fallback pause before the next trick when the hold toggle is off. */
    private fun interTrickPauseMillis(speed: AnimationSpeed): Long = when (speed) {
        AnimationSpeed.SLOW -> 1800L
        AnimationSpeed.NORMAL -> 1000L
        AnimationSpeed.FAST -> 400L
        AnimationSpeed.OFF -> 0L
    }

    /** Key of the last completed trick the player has acknowledged (tapped past). */
    private val trickAcked = MutableStateFlow(0)

    private fun trickKey(handNumber: Int, trickNumber: Int) = handNumber * 1000 + trickNumber

    /**
     * Called by the UI when the player taps the completed trick away; releases the next leader.
     * The completed trick stays on the felt until then so it can be memorised for counting.
     */
    fun acknowledgeTrick(handNumber: Int, trickNumber: Int) {
        trickAcked.value = maxOf(trickAcked.value, trickKey(handNumber, trickNumber))
    }

    private val _botNames = MutableStateFlow<Map<Seat, String>>(emptyMap())

    /** Display names for the bot seats (1 until playerCount), fixed per game. */
    val botNames: StateFlow<Map<Seat, String>> = _botNames

    /** The human's view of the game, or null before a game starts. */
    val humanView: StateFlow<PlayerView?> = state
        .map { snapshot -> snapshot?.let { rules.view(it, humanSeat) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var gameJob: Job? = null

    fun newGame(
        seed: Long,
        playerCount: Int = 4,
        misereEnabled: Boolean = true,
        noTrumpsEnabled: Boolean = true,
        teamCount: Int = 2,
    ) {
        gameJob?.cancel()
        state.value = null
        handResultAcked.value = 0
        dealAnimationDone.value = 0
        trickAcked.value = 0
        rules = FiveHundredRules(
            playerCount = playerCount,
            misereEnabled = misereEnabled,
            noTrumpsEnabled = noTrumpsEnabled,
            teamCount = teamCount,
        )
        val gameRules = rules
        val names = BOT_NAMES.shuffled(Random(seed))
        _botNames.value = (1 until playerCount).associate { i -> Seat(i) to names[i - 1] }
        val players: Map<Seat, Player<PlayerView, Action>> = buildMap {
            put(humanSeat, human)
            for (i in 1 until playerCount) put(Seat(i), paced(StrategyPlayer(bot, Random(seed + i))))
        }
        gameJob = viewModelScope.launch {
            GameDriver(gameRules, players).play(gameRules.newGame(seed)) { snapshot -> state.value = snapshot }
        }
    }

    /** Wraps a bot so its turns are visibly paced by the current [animationSpeed]. */
    private fun paced(inner: Player<PlayerView, Action>): Player<PlayerView, Action> =
        Player { view ->
            // The hand's very first bidder holds until the previous hand's result dialog has been
            // dismissed (nothing — not even the shuffle — moves while the player reads it), then
            // until the UI reports the shuffle + deal animation has actually finished — a signal,
            // not a timer, so slow devices can't have the auction start mid-deal. The timeout is a
            // deadlock backstop (e.g. activity recreated mid-deal, where no signal will come).
            if (view.phase == Phase.BIDDING && view.biddingHistory.isEmpty()) {
                if (view.lastHandResult != null && view.winner == null) {
                    handResultAcked.first { it >= view.handNumber }
                }
                if (animationSpeed.value != AnimationSpeed.OFF) {
                    withTimeoutOrNull(dealPauseMillis(animationSpeed.value) * 3) {
                        dealAnimationDone.first { it >= view.handNumber }
                    }
                    delay(animationSpeed.value.botDelayMillis)
                }
            }
            // A bot about to lead a fresh trick (not the hand's first): with "Hold tricks" on, wait
            // until the player taps the completed trick away (or turns the hold off mid-wait);
            // otherwise a short timed pause keeps the trick readable. Nothing at OFF.
            // (The hold, like all pacing, is inert at OFF — which also keeps tests deterministic.)
            if (view.currentTrick.isEmpty() && view.trickNumber > 0 &&
                animationSpeed.value != AnimationSpeed.OFF
            ) {
                if (holdTricks.value) {
                    val key = trickKey(view.handNumber, view.trickNumber)
                    combine(trickAcked, holdTricks) { acked, hold -> !hold || acked >= key }
                        .first { it }
                } else {
                    delay(interTrickPauseMillis(animationSpeed.value))
                }
            }
            delay(animationSpeed.value.botDelayMillis)
            inner.decide(view)
        }

    /** Hold before the first bid of a hand — covers GameScreen's shuffle + deal animation. */
    private fun dealPauseMillis(speed: AnimationSpeed): Long = when (speed) {
        AnimationSpeed.SLOW -> 6800L
        AnimationSpeed.NORMAL -> 4400L
        AnimationSpeed.FAST -> 2000L
        AnimationSpeed.OFF -> 0L
    }

    fun placeBid(bid: Bid) = submit(Action.PlaceBid(bid))
    fun discard(cards: List<Card>) = submit(Action.ExchangeKitty(cards))
    fun playCard(card: Card, nominate: Suit? = null) = submit(Action.PlayCard(card, nominate))

    // trySubmit drops the action unless the engine is actually waiting, so a double-tap (or a tap
    // racing a state change) can't queue an action that would answer a *later* prompt.
    private fun submit(action: Action) {
        human.trySubmit(action)
    }

    private companion object {
        /** Pool of friendly bot names; `playerCount - 1` distinct ones are drawn per game, seeded by the game seed. */
        val BOT_NAMES = listOf(
            "Alice", "Bruce", "Clancy", "Daisy", "Edna", "Frank", "Gus", "Hazel",
            "Ivy", "Mabel", "Ned", "Olive", "Pearl", "Ray", "Thelma", "Wally",
        )
    }
}
