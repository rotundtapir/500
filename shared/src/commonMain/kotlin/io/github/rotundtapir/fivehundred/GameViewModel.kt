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
import io.github.rotundtapir.cardkit.ui.settings.AnimationSpeed
import io.github.rotundtapir.cardkit.ui.settings.BotSkill
import io.github.rotundtapir.fivehundred.ai.AdvancedBot
import io.github.rotundtapir.fivehundred.ai.AdvancedBotPlayer
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.ai.SearchConfig
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
import kotlin.time.Duration.Companion.milliseconds

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

    private val _currentSeed = MutableStateFlow<Long?>(null)

    /** How quickly bot turns play out — set by the activity from the persisted setting, read live. */
    val animationSpeed = MutableStateFlow(AnimationSpeed.NORMAL)

    /** Whether completed tricks stay on the felt until tapped away — set live from the UI toggle. */
    val holdTricks = MutableStateFlow(false)

    /** Signal-driven pacing shared with the online client; see [fiveHundredPacingGates]. */
    private val pacing = fiveHundredPacingGates(animationSpeed, holdTricks)

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

    /**
     * Cheat-only, offline-only: every seat's hand, straight off the local [GameState].
     *
     * Deliberately NOT a [PlayerView] field. In a local game this process is the authority and
     * already holds the unredacted state, so the cheat needs no engine change; online, the client
     * never receives other hands at all, so there is nothing here for a forgotten conditional to
     * leak. That is the difference between an invariant and a guard — see #51.
     */
    val allHands: StateFlow<Map<Seat, List<Card>>> = state
        .map { it?.hands ?: emptyMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * The seed the current match was built from, for the cheats menu's show/copy affordance.
     *
     * Offline only, and load-bearing: the engine is public and strictly seed-deterministic, so a
     * seed IS the full game state — anyone holding it can reconstruct all four hands and every
     * future deal. It must never be rendered during an online game (`online != null`), which is
     * why this lives on the local ViewModel and no wire message carries it.
     */
    val currentSeed: StateFlow<Long?> = _currentSeed

    private var gameJob: Job? = null

    /** The arguments the current match was started with, so a cheat re-deal can reuse them. */
    private var lastSetup: GameSetup? = null

    /** Everything [newGame] needs except the seed — what "re-deal this hand" keeps constant. */
    private data class GameSetup(
        val playerCount: Int,
        val misereEnabled: Boolean,
        val noTrumpsEnabled: Boolean,
        val teamCount: Int,
        val botSkill: BotSkill,
        val aiBudgetMillis: Long?,
    )

    /**
     * Cheat: deal again from a fresh seed, keeping the table exactly as it is.
     *
     * This restarts the match (scores and hand history reset) rather than re-rolling the current
     * hand in place: `rngSeed` evolves per deal, so re-dealing one hand mid-match would need the
     * hand's entry seed retained in the engine. For "cycle deals until an interesting one appears",
     * which is what this is for, a restart is what's wanted — and it is honest about what happened.
     */
    fun cheatRedeal(seed: Long) {
        val setup = lastSetup ?: return
        newGame(
            seed = seed,
            playerCount = setup.playerCount,
            misereEnabled = setup.misereEnabled,
            noTrumpsEnabled = setup.noTrumpsEnabled,
            teamCount = setup.teamCount,
            botSkill = setup.botSkill,
            aiBudgetMillis = setup.aiBudgetMillis,
        )
    }

    fun newGame(
        seed: Long,
        playerCount: Int = 4,
        misereEnabled: Boolean = true,
        noTrumpsEnabled: Boolean = true,
        teamCount: Int = 2,
        botSkill: BotSkill = BotSkill.STANDARD,
        // Test knob: shrinks the advanced bot's trick budget to this many ms (bid 3x, kitty 2x —
        // the production ratio), so instrumented/e2e suites think at test speed. Null = defaults.
        aiBudgetMillis: Long? = null,
    ) {
        gameJob?.cancel()
        state.value = null
        pacing.reset()
        _currentSeed.value = seed
        lastSetup = GameSetup(playerCount, misereEnabled, noTrumpsEnabled, teamCount, botSkill, aiBudgetMillis)
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
            for (i in 1 until playerCount) put(Seat(i), paced(botPlayer(botSkill, gameRules, seed, i, aiBudgetMillis)))
        }
        gameJob = viewModelScope.launch {
            GameDriver(gameRules, players).play(gameRules.newGame(seed)) { snapshot -> state.value = snapshot }
        }
    }

    /**
     * The bot for seat [i]: the shared heuristic, or — with the Advanced AI setting on — a
     * per-seat Monte-Carlo [AdvancedBot] (stateful card tracking) run off the main thread by
     * [AdvancedBotPlayer]. Both take `Random(seed + i)`, the established per-seat convention.
     */
    private fun botPlayer(
        botSkill: BotSkill,
        gameRules: FiveHundredRules,
        seed: Long,
        i: Int,
        aiBudgetMillis: Long?,
    ): Player<PlayerView, Action> = when (botSkill) {
        BotSkill.STANDARD -> StrategyPlayer(bot, Random(seed + i))
        BotSkill.ADVANCED -> AdvancedBotPlayer(
            AdvancedBot(rules = gameRules, config = advancedConfig(aiBudgetMillis)),
            Random(seed + i),
        )
    }

    private fun advancedConfig(budgetMillis: Long?): SearchConfig =
        if (budgetMillis == null) {
            SearchConfig()
        } else {
            SearchConfig(
                bidBudget = (budgetMillis * 3).milliseconds,
                kittyBudget = (budgetMillis * 2).milliseconds,
                playBudget = budgetMillis.milliseconds,
            )
        }

    /** Wraps a bot so its turns are visibly paced by the current [animationSpeed] (see [fiveHundredPacingGates]). */
    private fun paced(inner: Player<PlayerView, Action>): Player<PlayerView, Action> =
        Player { view ->
            pacing.awaitGates(view.transitions)
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
