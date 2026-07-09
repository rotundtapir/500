// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import io.github.rotundtapir.fivehundred.net.ConnectionState
import io.github.rotundtapir.fivehundred.net.Emote
import io.github.rotundtapir.fivehundred.net.EmoteReceived
import io.github.rotundtapir.fivehundred.rememberGameSoundEffects
import io.github.rotundtapir.fivehundred.online.OnlineScreen
import io.github.rotundtapir.fivehundred.online.OnlineViewModel
import io.github.rotundtapir.fivehundred.ui.GameMode
import io.github.rotundtapir.fivehundred.ui.GameScreen
import io.github.rotundtapir.fivehundred.ui.SettingsControls
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

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

    updateRequired?.let { message ->
        AlertDialog(
            onDismissRequest = onExit,
            title = { Text("Update required") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onExit, modifier = Modifier.testTag("updateRequired")) { Text("OK") } },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("Notice") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
        )
    }

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
    val remaining by vm.session.turnRemainingMillis.collectAsState()
    // Called unconditionally (before the null branch) to satisfy Compose's stable-call-order rule.
    val playSound = rememberGameSoundEffects(view = view, volume = soundVolume)

    val current = view
    if (current == null) {
        WaitingBox("Starting game…")
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
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
        )
        val secondsLeft = remaining?.let { (it / 1000).toInt() }
        if (secondsLeft != null && !current.isMyTurn) {
            TurnCountdown(secondsLeft, Modifier.align(Alignment.TopCenter))
        }
        EmoteBar(vm.emotes, vm::sendEmote, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TurnCountdown(seconds: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(top = 4.dp).testTag("turnCountdown"),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Text("Waiting… ${seconds}s", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun EmoteBar(
    emotes: SharedFlow<EmoteReceived>,
    onEmote: (Emote) -> Unit,
    modifier: Modifier = Modifier,
) {
    var latest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        emotes.collect { received ->
            latest = "${received.emote.name.lowercase().replace('_', ' ')}!"
            delay(EMOTE_TOAST_MILLIS)
            latest = null
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.padding(bottom = 4.dp)) {
        latest?.let {
            Surface(color = MaterialTheme.colorScheme.primary) {
                Text(it, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(8.dp))
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EMOTE_LABELS.forEach { (emote, label) ->
                TextButton(onClick = { onEmote(emote) }, modifier = Modifier.testTag("emote:${emote.name}")) {
                    Text(label)
                }
            }
        }
    }
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

private const val EMOTE_TOAST_MILLIS = 2000L
private val EMOTE_LABELS = listOf(
    Emote.WELL_PLAYED to "Well played",
    Emote.NICE_HAND to "Nice hand",
    Emote.OOPS to "Oops",
    Emote.THINKING to "Hmm",
    Emote.HURRY_UP to "Hurry up",
    Emote.GOOD_GAME to "GG",
)
