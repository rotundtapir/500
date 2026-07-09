// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.fivehundred.ui.GameMode
import io.github.rotundtapir.fivehundred.ui.GameScreen
import io.github.rotundtapir.fivehundred.ui.HomeScreen
import io.github.rotundtapir.fivehundred.ui.SettingsControls
import io.github.rotundtapir.fivehundred.ui.TUTORIAL_SEED
import io.github.rotundtapir.fivehundred.ui.TutorialScriptState
import kotlinx.coroutines.launch

/**
 * The whole game UI, shared by every entry point (Android activity, browser). Each entry point
 * supplies its platform pieces: a [Monetization] implementation, a [SettingsRepository] backend,
 * the [AppConfig] values AGP's BuildConfig used to carry, a seed source, and optional overrides
 * (intent extras on Android, URL parameters on web) that pin animation speed and volume for tests.
 */
@Composable
fun FiveHundredApp(
    monetization: Monetization,
    settings: SettingsRepository,
    appConfig: AppConfig,
    nextSeed: () -> Long,
    animationSpeedOverride: AnimationSpeed? = null,
    soundVolumeOverride: Float? = null,
    // Injected as a parameter (an explicit dependency) rather than fetched inside the body. The
    // default keeps the wasm-safe explicit initializer — a bare viewModel() uses the reflection
    // factory, which is JVM-only and throws on wasm.
    vm: GameViewModel = viewModel { GameViewModel() },
) {
    // Saveable so an in-progress game survives activity recreation (rotation, theme change, …);
    // the game itself lives in the ViewModel. (On web this degrades to remember {}.)
    var inGame by rememberSaveable { mutableStateOf(false) }
    val view by vm.humanView.collectAsState()
    val botNames by vm.botNames.collectAsState()

    val persistedSpeed by settings.animationSpeed.collectAsState(initial = SettingsDefaults.ANIMATION_SPEED)
    val animationSpeed = animationSpeedOverride ?: persistedSpeed
    val sortByDefault by settings.sortHandByDefault.collectAsState(initial = SettingsDefaults.SORT_HAND_BY_DEFAULT)
    LaunchedEffect(animationSpeed) { vm.animationSpeed.value = animationSpeed }
    val scope = rememberCoroutineScope()
    val misereEnabled by settings.misereEnabled.collectAsState(initial = SettingsDefaults.MISERE_ENABLED)
    val noTrumpsEnabled by settings.noTrumpsEnabled.collectAsState(initial = SettingsDefaults.NO_TRUMPS_ENABLED)
    val persistedVolume by settings.soundVolume.collectAsState(initial = SettingsDefaults.SOUND_VOLUME)
    val soundVolume = soundVolumeOverride ?: persistedVolume
    // One sound engine for the whole app: reacts to game-state transitions, and hands back a play
    // function that the dealing animation's sound hook uses for shuffle/deal effects.
    val playSound = rememberGameSoundEffects(view = view, volume = soundVolume)
    val holdTricks by settings.holdTricks.collectAsState(initial = SettingsDefaults.HOLD_TRICKS)
    // Current values + write-through callbacks as one unit, for the screens' shared settings dialog.
    val settingsControls = SettingsControls(
        animationSpeed = animationSpeed,
        onCycleAnimationSpeed = { scope.launch { settings.setAnimationSpeed(animationSpeed.next()) } },
        sortByDefault = sortByDefault,
        onSetSortByDefault = { value -> scope.launch { settings.setSortHandByDefault(value) } },
        holdTricks = holdTricks,
        onSetHoldTricks = { value -> scope.launch { settings.setHoldTricks(value) } },
        soundVolume = soundVolume,
        onSetSoundVolume = { value -> scope.launch { settings.setSoundVolume(value) } },
        misereEnabled = misereEnabled,
        onSetMisereEnabled = { value -> scope.launch { settings.setMisereEnabled(value) } },
        noTrumpsEnabled = noTrumpsEnabled,
        onSetNoTrumpsEnabled = { value -> scope.launch { settings.setNoTrumpsEnabled(value) } },
    )
    // Stored by name so rememberSaveable needs no custom Saver.
    var modeName by rememberSaveable { mutableStateOf(GameMode.FOUR_PLAYER.name) }
    val mode = GameMode.valueOf(modeName)

    // The interactive "How to play" tutorial: a scripted hand on TUTORIAL_SEED. The step index is
    // saveable so an activity recreation mid-tutorial resumes at the same point in the script.
    var tutorialActive by rememberSaveable { mutableStateOf(false) }
    var tutorialStepIndex by rememberSaveable { mutableIntStateOf(0) }
    // The tutorial forces the trick hold on so each completed trick waits to be explained by the
    // bubble; otherwise the user's setting applies. (Like all pacing, the hold is inert at OFF.)
    LaunchedEffect(holdTricks, tutorialActive) { vm.holdTricks.value = holdTricks || tutorialActive }
    val startTutorial: () -> Unit = {
        // The tutorial script depends on the exact table: 4 players, 2 teams, misère and no-trumps
        // enabled — pinned here regardless of the user's mode selection and house-rule settings.
        vm.newGame(TUTORIAL_SEED, playerCount = 4, misereEnabled = true, noTrumpsEnabled = true, teamCount = 2)
        tutorialStepIndex = 0
        tutorialActive = true
        inGame = true
    }

    CompositionLocalProvider(LocalAppConfig provides appConfig) {
        // A single HomeScreen call also covers the first frame after newGame() (inGame set, view
        // still null), so its internal state survives the transition instead of being rebuilt.
        val current = view
        if (!inGame || current == null) {
            HomeScreen(
                monetization = monetization,
                onNewGame = {
                    vm.newGame(nextSeed(), mode.players, misereEnabled, noTrumpsEnabled, mode.teams)
                    tutorialActive = false
                    inGame = true
                },
                onStartTutorial = startTutorial,
                settings = settingsControls,
                mode = mode,
                onModeChange = { modeName = it.name },
            )
        } else {
            GameScreen(
                view = current,
                botNames = botNames,
                settings = settingsControls,
                monetization = monetization,
                onBid = vm::placeBid,
                onDiscard = vm::discard,
                onPlay = { card -> vm.playCard(card) },
                onExit = {
                    inGame = false
                    tutorialActive = false
                },
                tutorial = if (tutorialActive) {
                    TutorialScriptState(tutorialStepIndex) { tutorialStepIndex++ }
                } else null,
                onResultDismiss = vm::acknowledgeHandResult,
                onDealAnimationFinish = vm::dealAnimationFinished,
                onTrickAcknowledge = vm::acknowledgeTrick,
                soundHook = playSound,
            )
        }
    }
}
