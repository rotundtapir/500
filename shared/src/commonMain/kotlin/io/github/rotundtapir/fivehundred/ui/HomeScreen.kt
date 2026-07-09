// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.cardkit.monetization.Monetization

/**
 * A game mode the home screen offers: a table size plus its team structure. [players] and [teams]
 * feed straight into `FiveHundredRules(playerCount, teamCount)`.
 */
enum class GameMode(
    val players: Int,
    val teams: Int,
    val title: String,
    val subtitle: String,
    val tag: String,
) {
    TWO_PLAYER(players = 2, teams = 2, title = "2 players", subtitle = "head to head", tag = "mode:2p"),
    FOUR_PLAYER(players = 4, teams = 2, title = "4 players", subtitle = "2 teams of 2", tag = "mode:4p"),
    SIX_PLAYER_TWO_TEAMS(players = 6, teams = 2, title = "6 players", subtitle = "2 teams of 3", tag = "mode:6p2t"),
    SIX_PLAYER_THREE_TEAMS(players = 6, teams = 3, title = "6 players", subtitle = "3 teams of 2", tag = "mode:6p3t"),
}

@Composable
fun HomeScreen(
    monetization: Monetization,
    onNewGame: () -> Unit,
    onStartTutorial: () -> Unit,
    settings: SettingsControls,
    mode: GameMode,
    onModeChange: (GameMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showTutorialIntro by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .testTag("settingsButton"),
            ) {
                Icon(
                    imageVector = SettingsIcon,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("500", fontSize = 72.sp, fontWeight = FontWeight.Bold)
                Text("Australian rules · you vs the bots", fontSize = 16.sp)
                Spacer(Modifier.height(24.dp))

                // The four game modes, as a compact 2×2 grid of two-line buttons.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameMode.entries.chunked(2).forEach { rowModes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowModes.forEach { m ->
                                GameModeButton(
                                    mode = m,
                                    selected = m == mode,
                                    onClick = { onModeChange(m) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onNewGame,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFAFAFA),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("New Game", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = { showTutorialIntro = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("walkthroughButton"),
                ) { Text("How to play") }

            }
        }
    }

    if (showTutorialIntro) {
        TutorialIntroDialog(
            onStart = {
                showTutorialIntro = false
                onStartTutorial()
            },
            onDismiss = { showTutorialIntro = false },
        )
    }
    if (showSettings) {
        SettingsDialog(
            settings = settings,
            inGame = false,
            monetization = monetization,
            onDismiss = { showSettings = false },
        )
    }
}

/** One option in the game-mode selector: player count over its team structure; the selected one gets filled emphasis. */
@Composable
private fun GameModeButton(
    mode: GameMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    OutlinedButton(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = onBackground.copy(alpha = 0.15f),
                contentColor = onBackground,
            )
        } else {
            ButtonDefaults.outlinedButtonColors(contentColor = onBackground.copy(alpha = 0.7f))
        },
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            onBackground.copy(alpha = if (selected) 1f else 0.4f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier.testTag(mode.tag),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                mode.title,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
            Text(
                mode.subtitle,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
