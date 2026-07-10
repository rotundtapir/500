// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui.online

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.LocalLinkSharer
import io.github.rotundtapir.fivehundred.net.GameOver
import io.github.rotundtapir.fivehundred.net.LobbyState
import io.github.rotundtapir.fivehundred.net.RoomPhase
import io.github.rotundtapir.fivehundred.net.SeatInfo
import io.github.rotundtapir.fivehundred.online.JoinLink
import kotlinx.coroutines.delay

/**
 * The lobby room: the join code, the seats grouped by team, ready toggles, and (for the creator) the
 * Start button that fills empty seats with bots. After a game it becomes the post-game screen, with
 * Rematch / Disband for the creator and the final scoreline.
 */
@Composable
internal fun LobbyRoomScreen(
    state: LobbyState,
    gameOver: GameOver?,
    onPickSeat: (Seat) -> Unit,
    onSetReady: (Boolean) -> Unit,
    onStart: () -> Unit,
    onRematch: () -> Unit,
    onDisband: () -> Unit,
    onLeave: () -> Unit,
) {
    val isCreator = state.yourSeat == state.creatorSeat
    val presentHumans = state.seats.filter { it.connected }
    // The host isn't part of the ready check — clicking Start is their readiness. Everyone else who
    // is present must be ready first (a solo host can always start; empty seats become bots).
    val guestsReady = presentHumans.filter { it.seat != state.creatorSeat }.all { it.ready }
    val myReady = state.seats.firstOrNull { it.seat == state.yourSeat }?.ready == true
    val teamCount = state.config.teamCount
    val finished = state.phase == RoomPhase.FINISHED

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (finished) "Game over" else "Lobby", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Code", style = MaterialTheme.typography.labelMedium)
            Text(
                state.joinCode,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("lobbyCode"),
            )

            // The host can share an invite link (…/?joinCode=CODE) that opens the game in the browser
            // or, for players with the app installed, straight into it. Android → native share sheet;
            // web → copy to clipboard (with a brief confirmation).
            if (isCreator && !finished) {
                val sharer = LocalLinkSharer.current
                var copied by remember { mutableStateOf(false) }
                if (copied) LaunchedEffect(copied) { delay(COPIED_CONFIRM_MILLIS); copied = false }
                OutlinedButton(
                    onClick = { copied = sharer.share("Join my game of 500", JoinLink.forCode(state.joinCode)) },
                    modifier = Modifier.fillMaxWidth().testTag("shareInvite"),
                ) { Text("Share invite link") }
                if (copied) {
                    Text(
                        "Link copied to clipboard",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (finished && gameOver != null) {
                FinalScores(gameOver)
            }

            // Seats grouped by team (team = seat.index % teamCount).
            for (team in 0 until teamCount) {
                Text("Team ${team + 1}", style = MaterialTheme.typography.labelLarge)
                state.seats.filter { it.seat.index % teamCount == team }.forEach { info ->
                    SeatRow(
                        info = info,
                        isYou = info.seat == state.yourSeat,
                        isHost = info.seat == state.creatorSeat,
                        canClaim = !finished && !info.connected && state.yourSeat != null,
                        onClaim = { onPickSeat(info.seat) },
                    )
                }
            }

            if (!finished) {
                if (isCreator) {
                    Button(
                        onClick = onStart,
                        enabled = guestsReady,
                        modifier = Modifier.fillMaxWidth().testTag("startGame"),
                    ) { Text("Start (empty seats become bots)") }
                } else {
                    // Guests mark themselves ready; the host just clicks Start.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Ready")
                        Switch(checked = myReady, onCheckedChange = onSetReady, modifier = Modifier.testTag("readyToggle"))
                    }
                    Text("Waiting for the host to start…", style = MaterialTheme.typography.labelMedium)
                }
            } else if (isCreator) {
                Button(onClick = onRematch, modifier = Modifier.fillMaxWidth().testTag("rematch")) {
                    Text("Play again")
                }
                OutlinedButton(onClick = onDisband, modifier = Modifier.fillMaxWidth().testTag("disband")) {
                    Text("Disband lobby")
                }
            } else {
                Text("Waiting for the host…", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().testTag("leaveLobby")) {
                Text("Leave")
            }
        }
    }
}

@Composable
private fun SeatRow(info: SeatInfo, isYou: Boolean, isHost: Boolean, canClaim: Boolean, onClaim: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = when {
                info.name.isNotBlank() -> info.name + if (isYou) " (you)" else ""
                else -> "Open seat"
            }
            Text(label, fontWeight = if (isYou) FontWeight.Bold else FontWeight.Normal)
            when {
                canClaim -> OutlinedButton(
                    onClick = onClaim,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)),
                    modifier = Modifier.testTag("claimSeat${info.seat.index}"),
                ) { Text("Sit here") }
                // The host has no ready state — clicking Start is their readiness — so their seat
                // shows "Host" rather than a ready/not-ready status.
                info.connected && isHost -> Text("Host", color = MaterialTheme.colorScheme.primary)
                info.connected && info.ready -> Text("Ready", color = MaterialTheme.colorScheme.primary)
                info.connected -> Text("Not ready", style = MaterialTheme.typography.labelMedium)
                else -> Text("")
            }
        }
    }
}

private const val COPIED_CONFIRM_MILLIS = 2500L

@Composable
private fun FinalScores(gameOver: GameOver) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Team ${gameOver.winnerTeam + 1} wins", fontWeight = FontWeight.Bold)
        gameOver.scores.entries.sortedBy { it.key }.forEach { (team, score) ->
            Text("Team ${team + 1}: $score")
        }
    }
}
