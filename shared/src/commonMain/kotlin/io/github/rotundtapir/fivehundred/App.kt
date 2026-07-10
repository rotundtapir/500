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
import io.github.rotundtapir.fivehundred.online.OnlineViewModel
import io.github.rotundtapir.fivehundred.ui.BotSetupScreen
import io.github.rotundtapir.fivehundred.ui.GameMode
import io.github.rotundtapir.fivehundred.ui.GameScreen
import io.github.rotundtapir.fivehundred.ui.HomeScreen
import io.github.rotundtapir.fivehundred.ui.SettingsControls
import io.github.rotundtapir.fivehundred.ui.TUTORIAL_SEED
import io.github.rotundtapir.fivehundred.ui.TutorialScriptState
import io.github.rotundtapir.fivehundred.ui.online.OnlineFlow
import kotlinx.coroutines.launch

/**
 * The whole game UI, shared by every entry point (Android activity, browser). Each entry point
 * supplies its platform pieces: a [Monetization] implementation, a [SettingsRepository] backend,
 * the [AppConfig] values AGP's BuildConfig used to carry, a seed source, and optional overrides
 * (intent extras on Android, URL parameters on web) that pin animation speed and volume for tests.
 */
// FiveHundredApp owns the ViewModels and hands the online one to OnlineFlow (the app's own flow, not
// a reusable component); threading its ~15 lobby/game callbacks individually would be far less clear.
@Suppress("ViewModelForwarding")
@Composable
fun FiveHundredApp(
    monetization: Monetization,
    settings: SettingsRepository,
    appConfig: AppConfig,
    nextSeed: () -> Long,
    animationSpeedOverride: AnimationSpeed? = null,
    soundVolumeOverride: Float? = null,
    // Test overrides (web URL params): seed the server URL / player name so e2e can point at a local
    // server and skip canvas text entry.
    serverUrlOverride: String? = null,
    playerNameOverride: String? = null,
    // Injected as a parameter (an explicit dependency) rather than fetched inside the body. The
    // default keeps the wasm-safe explicit initializer — a bare viewModel() uses the reflection
    // factory, which is JVM-only and throws on wasm.
    vm: GameViewModel = viewModel { GameViewModel() },
    // Explicit initializer for the same wasm reason as [vm]: the reflective factory throws on wasm.
    onlineVm: OnlineViewModel = viewModel { OnlineViewModel() },
) {
    // Which top-level screen shows. Saveable so an in-progress game survives activity recreation;
    // the game itself lives in the ViewModel. (On web this degrades to remember {}.)
    var appScreen by rememberSaveable { mutableStateOf(AppScreen.HOME.name) }
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
    val persistedServerUrl by settings.serverUrl.collectAsState(initial = SettingsDefaults.SERVER_URL)
    // A ?serverUrl= override wins for this session only and is NEVER persisted: otherwise a shared
    // link like ?serverUrl=wss://evil could permanently repoint a victim's online play (and control
    // the trusted "update required" text) even after the tab is closed. Same treatment as the
    // animation-speed / sound-volume test overrides above.
    val serverUrl = serverUrlOverride ?: persistedServerUrl
    val playerName by settings.playerName.collectAsState(initial = SettingsDefaults.PLAYER_NAME)
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
        serverUrl = serverUrl,
        onSetServerUrl = { value -> scope.launch { settings.setServerUrl(value) } },
        playerName = playerName,
        onSetPlayerName = { value -> scope.launch { settings.setPlayerName(value) } },
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
    // The online game reuses the same pacing, so mirror the animation/hold settings into its VM.
    LaunchedEffect(animationSpeed) { onlineVm.animationSpeed.value = animationSpeed }
    LaunchedEffect(holdTricks) { onlineVm.holdTricks.value = holdTricks }
    // Player-name test override is seeded into settings (a display name is harmless to persist and
    // e2e relies on it to skip canvas text entry). The server-URL override is deliberately NOT
    // persisted — it is applied in memory via [serverUrl] above.
    LaunchedEffect(playerNameOverride) {
        playerNameOverride?.let { settings.setPlayerName(it) }
    }
    val startTutorial: () -> Unit = {
        // The tutorial script depends on the exact table: 4 players, 2 teams, misère and no-trumps
        // enabled — pinned here regardless of the user's mode selection and house-rule settings.
        vm.newGame(TUTORIAL_SEED, playerCount = 4, misereEnabled = true, noTrumpsEnabled = true, teamCount = 2)
        tutorialStepIndex = 0
        tutorialActive = true
        appScreen = AppScreen.GAME.name
    }

    CompositionLocalProvider(LocalAppConfig provides appConfig) {
        // A single HomeScreen call also covers the first frame after newGame() (screen set to GAME,
        // view still null), so its internal state survives the transition instead of being rebuilt.
        val current = view
        val onGameScreen = appScreen == AppScreen.GAME.name && current != null
        when {
            appScreen == AppScreen.ONLINE.name -> OnlineFlow(
                vm = onlineVm,
                settings = settingsControls,
                monetization = monetization,
                soundVolume = soundVolume,
                onExit = {
                    onlineVm.exit()
                    appScreen = AppScreen.HOME.name
                },
            )
            onGameScreen -> GameScreen(
                view = current!!,
                botNames = botNames,
                settings = settingsControls,
                monetization = monetization,
                onBid = vm::placeBid,
                onDiscard = vm::discard,
                onPlay = { card -> vm.playCard(card) },
                onExit = {
                    appScreen = AppScreen.HOME.name
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
            appScreen == AppScreen.BOT_SETUP.name -> BotSetupScreen(
                mode = mode,
                onModeChange = { modeName = it.name },
                onStart = {
                    vm.newGame(nextSeed(), mode.players, misereEnabled, noTrumpsEnabled, mode.teams)
                    tutorialActive = false
                    appScreen = AppScreen.GAME.name
                },
                onBack = { appScreen = AppScreen.HOME.name },
            )
            else -> HomeScreen(
                monetization = monetization,
                onPlayWithBots = { appScreen = AppScreen.BOT_SETUP.name },
                onStartTutorial = startTutorial,
                onPlayWithFriends = {
                    onlineVm.enter(serverUrl, appConfig.version, appConfig.platform)
                    appScreen = AppScreen.ONLINE.name
                },
                settings = settingsControls,
            )
        }
    }
}

/** Top-level screens the app switches between. */
private enum class AppScreen { HOME, BOT_SETUP, GAME, ONLINE }
