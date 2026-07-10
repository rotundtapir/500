// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui.online

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.fivehundred.LocalAppConfig
import io.github.rotundtapir.fivehundred.net.ConnectionState
import io.github.rotundtapir.fivehundred.net.Platform
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
    val pendingRejoin by vm.pendingRejoin.collectAsState()

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
    // Reconnecting landed us back in a room we were in: ask before dropping the player into it, so
    // returning to online mode doesn't silently resume a game they meant to step away from.
    pendingRejoin?.let { resumed ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Rejoin your game?") },
            text = { Text("You're still in game ${resumed.joinCode}. Rejoin it, or leave it for good?") },
            confirmButton = {
                TextButton(onClick = vm::confirmRejoin, modifier = Modifier.testTag("rejoinConfirm")) { Text("Rejoin") }
            },
            dismissButton = {
                TextButton(onClick = vm::abandonGame, modifier = Modifier.testTag("rejoinLeave")) { Text("Leave") }
            },
        )
    }
    // In-game rejections (stale/illegal move) auto-dismiss so they never block the board. On the
    // setup/lobby screens the error stays put — a bad or expired code, a full lobby, a name clash —
    // until the player's next action clears it, so the feedback can't be missed as a brief flash.
    if (screen == OnlineScreen.GAME) {
        error?.let { LaunchedEffect(it) { delay(ERROR_BANNER_MILLIS); vm.dismissError() } }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // The setup/lobby screens are static, and Compose for Web is slow to schedule a frame for a
        // purely network-driven state change (a join reply, a roster update, an error banner) — so
        // the screen can appear to do nothing after a tap until the next user interaction forces a
        // repaint. Keeping the frame clock ticking makes server pushes render promptly. Web-only and
        // off in-game, where deal/trick animations already drive frames.
        if (LocalAppConfig.current.platform == Platform.WEB && screen != OnlineScreen.GAME) {
            NetworkFrameKeepAlive()
        }
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
            OnlineScreen.JOIN -> {
                val prefillCode by vm.pendingJoinCode.collectAsState()
                JoinLobbyScreen(
                    playerName = settings.playerName,
                    onSetPlayerName = settings.onSetPlayerName,
                    initialCode = prefillCode ?: "",
                    onJoin = { name, code ->
                        settings.onSetPlayerName(name)
                        vm.joinLobby(code, name)
                    },
                    onBack = vm::backToEntry,
                )
            }
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
    var everConnected by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    // The normal initial connect is sub-second, so showing a banner the instant we're not-connected
    // just flashes a scary red bar on every entry. Only surface one if we're *still* not connected
    // after a short grace period; a fast (re)connect cancels this effect before it fires.
    LaunchedEffect(connection) {
        if (connection == ConnectionState.CONNECTED) {
            everConnected = true
            show = false
        } else {
            show = false
            delay(CONNECT_GRACE_MILLIS)
            show = true
        }
    }
    if (!show) return
    Surface(
        color = Color(0xFFB00020),
        modifier = Modifier.fillMaxWidth().safeDrawingPadding().testTag("connectionBanner"),
    ) {
        Text(
            if (everConnected) "Reconnecting…" else "Connecting…",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(6.dp),
        )
    }
}

/**
 * An invisible, endlessly-repeating animation whose only job is to keep the Compose frame clock
 * ticking on the otherwise-static online setup/lobby screens (see call site). The animated value is
 * consumed by an off-screen 1-px box so the animation is observed and never optimised away; alpha is
 * pinned to 0 so nothing is ever visible.
 */
@Composable
private fun NetworkFrameKeepAlive() {
    val transition = rememberInfiniteTransition(label = "netFramePump")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 600)),
        label = "netFramePump",
    )
    Box(Modifier.size(1.dp).alpha(phase * 0f))
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

// How long a socket may stay unconnected before the connection banner appears — long enough that the
// normal fast (re)connect never flashes it, short enough to still flag a genuinely stalled connect.
private const val CONNECT_GRACE_MILLIS = 1500L
