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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    ) {
        gameJob?.cancel()
        state.value = null
        rules = FiveHundredRules(
            playerCount = playerCount,
            misereEnabled = misereEnabled,
            noTrumpsEnabled = noTrumpsEnabled,
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
            // The hand's very first bidder holds until the dealing animation has played out, so the
            // auction doesn't visibly start mid-deal.
            if (view.phase == Phase.BIDDING && view.biddingHistory.isEmpty()) {
                delay(dealPauseMillis(animationSpeed.value))
            }
            // A bot about to lead a fresh trick (not the hand's first) pauses longer first, so the
            // just-completed trick — and the winner popup — can be read before play moves on.
            if (view.currentTrick.isEmpty() && view.trickNumber > 0) {
                delay(interTrickPauseMillis(animationSpeed.value))
            }
            delay(animationSpeed.value.botDelayMillis)
            inner.decide(view)
        }

    /** Extra pause before a bot leads the next trick, leaving the previous one readable. */
    private fun interTrickPauseMillis(speed: AnimationSpeed): Long = when (speed) {
        AnimationSpeed.SLOW -> 1800L
        AnimationSpeed.NORMAL -> 1000L
        AnimationSpeed.FAST -> 400L
        AnimationSpeed.OFF -> 0L
    }

    /** Hold before the first bid of a hand — matches GameScreen's dealing-animation duration. */
    private fun dealPauseMillis(speed: AnimationSpeed): Long = when (speed) {
        AnimationSpeed.SLOW -> 4200L
        AnimationSpeed.NORMAL -> 2500L
        AnimationSpeed.FAST -> 1200L
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
