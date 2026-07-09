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

    /** Whether completed tricks stay on the felt until tapped away — set live from the UI toggle. */
    val holdTricks = MutableStateFlow(false)

    /** Signal-driven pacing shared with the online client; see [PacingGates]. */
    private val pacing = PacingGates(animationSpeed, holdTricks)

    /** Called by the UI when the hand-result dialog is dismissed; unblocks the next hand. */
    fun acknowledgeHandResult(handNumber: Int) = pacing.acknowledgeHandResult(handNumber)

    /** Called by the UI when a hand's deal animation completes; releases the first bidder. */
    fun dealAnimationFinished(handNumber: Int) = pacing.dealAnimationFinished(handNumber)

    /**
     * Called by the UI when the player taps the completed trick away; releases the next leader.
     * The completed trick stays on the felt until then so it can be memorised for counting.
     */
    fun acknowledgeTrick(handNumber: Int, trickNumber: Int) = pacing.acknowledgeTrick(handNumber, trickNumber)

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
        pacing.reset()
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

    /** Wraps a bot so its turns are visibly paced by the current [animationSpeed] (see [PacingGates]). */
    private fun paced(inner: Player<PlayerView, Action>): Player<PlayerView, Action> =
        Player { view ->
            pacing.awaitGates(view)
            delay(pacing.botBeatMillis)
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

    internal companion object {
        /**
         * Pool of friendly bot names; `playerCount - 1` distinct ones are drawn per game, seeded by
         * the game seed. Internal so TutorialScriptTest can pin the tutorial's scripted names
         * (ai's TutorialTraceGenerator keeps a copy it cannot import — that test is the drift gate).
         */
        val BOT_NAMES = listOf(
            "Alice", "Bruce", "Clancy", "Daisy", "Edna", "Frank", "Gus", "Hazel",
            "Ivy", "Mabel", "Ned", "Olive", "Pearl", "Ray", "Thelma", "Wally",
        )
    }
}
