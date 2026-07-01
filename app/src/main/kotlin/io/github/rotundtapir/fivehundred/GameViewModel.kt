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
import io.github.rotundtapir.fivehundred.engine.PLAYERS
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
 * Drives a game of 500 for the human at seat 0 against three [FiveHundredBot] opponents.
 *
 * The engine's [GameDriver] runs in [viewModelScope]; every state transition is pushed to [humanView]
 * (the redacted, seat-0 projection) so the UI can render. Human decisions are fed back through a
 * [ChannelPlayer] — the same seam a remote opponent would use.
 */
class GameViewModel : ViewModel() {
    private val rules = FiveHundredRules()
    private val bot = FiveHundredBot()
    private val humanSeat = Seat(0)
    private val human = ChannelPlayer<PlayerView, Action>()

    private val state = MutableStateFlow<GameState?>(null)

    /** How quickly bot turns play out — set by the activity from the persisted setting, read live. */
    val animationSpeed = MutableStateFlow(AnimationSpeed.NORMAL)

    private val _botNames = MutableStateFlow<Map<Seat, String>>(emptyMap())

    /** Display names for the bot seats (1..3), fixed per game. */
    val botNames: StateFlow<Map<Seat, String>> = _botNames

    /** The human's view of the game, or null before a game starts. */
    val humanView: StateFlow<PlayerView?> = state
        .map { snapshot -> snapshot?.let { rules.view(it, humanSeat) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var gameJob: Job? = null

    fun newGame(seed: Long) {
        gameJob?.cancel()
        state.value = null
        val names = BOT_NAMES.shuffled(Random(seed))
        _botNames.value = (1 until PLAYERS).associate { i -> Seat(i) to names[i - 1] }
        val players: Map<Seat, Player<PlayerView, Action>> = buildMap {
            put(humanSeat, human)
            for (i in 1 until PLAYERS) put(Seat(i), paced(StrategyPlayer(bot, Random(seed + i))))
        }
        gameJob = viewModelScope.launch {
            GameDriver(rules, players).play(rules.newGame(seed)) { snapshot -> state.value = snapshot }
        }
    }

    /** Wraps a bot so its turns are visibly paced by the current [animationSpeed]. */
    private fun paced(inner: Player<PlayerView, Action>): Player<PlayerView, Action> =
        Player { view ->
            delay(animationSpeed.value.botDelayMillis)
            inner.decide(view)
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
        /** Pool of friendly bot names; three distinct ones are drawn per game, seeded by the game seed. */
        val BOT_NAMES = listOf(
            "Alice", "Bruce", "Clancy", "Daisy", "Edna", "Frank", "Gus", "Hazel",
            "Ivy", "Mabel", "Ned", "Olive", "Pearl", "Ray", "Thelma", "Wally",
        )
    }
}
