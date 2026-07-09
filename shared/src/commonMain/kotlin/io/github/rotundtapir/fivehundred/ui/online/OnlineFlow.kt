// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.fivehundred.net.ConnectionState
import io.github.rotundtapir.fivehundred.rememberGameSoundEffects
import io.github.rotundtapir.fivehundred.online.OnlineScreen
import io.github.rotundtapir.fivehundred.online.OnlineViewModel
import io.github.rotundtapir.fivehundred.ui.GameMode
import io.github.rotundtapir.fivehundred.ui.GameScreen
import io.github.rotundtapir.fivehundred.ui.OnlineGameControls
import io.github.rotundtapir.fivehundred.ui.SettingsControls
import kotlinx.coroutines.delay

/**
 * The online mode's screen switch: entry → create/join → lobby → game → post-game, driven by
 * [OnlineViewModel.screen]. Renders the shared [GameScreen] for the live game, wrapped in online
 * chrome (connection banner, turn countdown, emotes). [onExit] returns to the home screen.
 */
// This flow drives every online screen from the one OnlineViewModel; forwarding it to the private
// per-screen helpers below is intentional and clearer than threading each screen's callbacks.
@Suppress("ViewModelForwarding")
@Composable
fun OnlineFlow(
    vm: OnlineViewModel,
    settings: SettingsControls,
    monetization: Monetization,
    soundVolume: Float,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screen by vm.screen.collectAsState()
    val error by vm.errorMessage.collectAsState()
    val updateRequired by vm.updateRequired.collectAsState()

    // Version gate is terminal — keep it modal. Everything else (stale/illegal/rate-limited) is a
    // brief, non-blocking banner so a rejected move never stalls the game behind a dialog.
    updateRequired?.let { message ->
        AlertDialog(
            onDismissRequest = onExit,
            title = { Text("Update required") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onExit, modifier = Modifier.testTag("updateRequired")) { Text("OK") } },
        )
    }
    error?.let { LaunchedEffect(it) { delay(ERROR_BANNER_MILLIS); vm.dismissError() } }

    Box(modifier = modifier.fillMaxSize()) {
        when (screen) {
            OnlineScreen.ENTRY -> OnlineEntryScreen(
                playerName = settings.playerName,
                onSetPlayerName = settings.onSetPlayerName,
                serverUrl = settings.serverUrl,
                onCreate = vm::goToCreate,
                onJoin = vm::goToJoin,
                onBack = onExit,
            )
            OnlineScreen.CREATE -> CreateLobbyScreen(
                onCreate = { mode: GameMode, turnTimeout, idle ->
                    vm.createLobby(settings.playerName, mode.players, mode.teams, turnTimeout, idle)
                },
                onBack = vm::backToEntry,
            )
            OnlineScreen.JOIN -> JoinLobbyScreen(
                onJoin = { code -> vm.joinLobby(code, settings.playerName) },
                onBack = vm::backToEntry,
            )
            OnlineScreen.LOBBY -> LobbyScreenHost(vm)
            OnlineScreen.GAME -> OnlineGame(vm, settings, monetization, soundVolume)
        }
        ConnectionBanner(vm)
        error?.let { message ->
            Box(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.padding(top = 8.dp).testTag("errorBanner"),
                ) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LobbyScreenHost(vm: OnlineViewModel) {
    val lobby by vm.lobby.collectAsState()
    val gameOver by vm.gameOver.collectAsState()
    lobby?.let { state ->
        LobbyRoomScreen(
            state = state,
            gameOver = gameOver,
            onPickSeat = vm::pickSeat,
            onSetReady = vm::setReady,
            onStart = vm::startGame,
            onRematch = vm::requestRematch,
            onDisband = vm::disbandLobby,
            onLeave = vm::leaveLobby,
        )
    }
}

@Composable
private fun OnlineGame(
    vm: OnlineViewModel,
    settings: SettingsControls,
    monetization: Monetization,
    soundVolume: Float,
) {
    val view by vm.session.views.collectAsState()
    val seatNames by vm.seatNames.collectAsState()
    // Called unconditionally (before the null branch) to satisfy Compose's stable-call-order rule.
    val playSound = rememberGameSoundEffects(view = view, volume = soundVolume)
    // Stable across recompositions so GameScreen's incoming-emote collector isn't restarted.
    val onlineControls = remember(vm) { OnlineGameControls(vm.emotes, vm::sendEmote) }

    val current = view
    if (current == null) {
        WaitingBox("Starting game…")
        return
    }
    // Emotes + incoming toast are rendered inside GameScreen (top bar), so they respect the insets
    // and the ad slot instead of overlaying the hand / nav.
    GameScreen(
        view = current,
        botNames = seatNames,
        settings = settings,
        monetization = monetization,
        onBid = vm::placeBid,
        onDiscard = vm::discard,
        onPlay = { card -> vm.playCard(card) },
        onExit = vm::leaveLobby,
        onResultDismiss = vm::acknowledgeHandResult,
        onDealAnimationFinish = vm::dealAnimationFinished,
        onTrickAcknowledge = vm::acknowledgeTrick,
        soundHook = playSound,
        leaveConfirmText = "A bot will play your cards. You can rejoin with the room code.",
        online = onlineControls,
    )
}

@Composable
private fun ConnectionBanner(vm: OnlineViewModel) {
    val connection by vm.connection.collectAsState()
    if (connection == ConnectionState.CONNECTED) return
    Surface(
        color = Color(0xFFB00020),
        modifier = Modifier.fillMaxWidth().safeDrawingPadding().testTag("connectionBanner"),
    ) {
        Text(
            "Reconnecting…",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(6.dp),
        )
    }
}

@Composable
private fun WaitingBox(message: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Text(message, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

private const val ERROR_BANNER_MILLIS = 2800L
