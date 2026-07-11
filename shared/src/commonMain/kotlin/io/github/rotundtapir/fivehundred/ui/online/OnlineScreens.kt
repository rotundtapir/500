// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui.online

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.fivehundred.net.Names
import io.github.rotundtapir.fivehundred.ui.GameMode
import io.github.rotundtapir.fivehundred.ui.GameModeButton
import io.github.rotundtapir.fivehundred.ui.OnBackgroundOutlinedButton
import io.github.rotundtapir.fivehundred.ui.onBackgroundFieldColors

/** Common frame for the online setup screens: a title, a scrollable body, and a back button. */
@Composable
internal fun OnlineScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            content()
            TextButton(
                onClick = onBack,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.testTag("onlineBack"),
            ) { Text("Back") }
        }
    }
}

/**
 * Entry point for online play: choose a display name, then create or join a lobby. The name is
 * validated with the same [Names] rules the server enforces and persisted so it prefills next time.
 */
@Composable
internal fun OnlineEntryScreen(
    playerName: String,
    onSetPlayerName: (String) -> Unit,
    serverUrl: String,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(playerName) }
    val valid = Names.isValid(name)
    OnlineScaffold(title = "Play with friends", onBack = onBack) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                if (Names.isValid(it)) onSetPlayerName(Names.normalize(it))
            },
            label = { Text("Your name") },
            singleLine = true,
            isError = name.isNotEmpty() && !valid,
            supportingText = {
                if (name.isNotEmpty() && !valid) Text("2–20 letters/digits; can't end with \"(bot)\"")
            },
            colors = onBackgroundFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("playerName"),
        )
        Button(
            onClick = onCreate,
            enabled = valid,
            modifier = Modifier.fillMaxWidth().testTag("createLobby"),
        ) { Text("Create a game") }
        OutlinedButton(
            onClick = onJoin,
            enabled = valid,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onBackground,
                disabledContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f),
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().testTag("joinLobby"),
        ) { Text("Join with a code") }
        Text(
            "Server: $serverUrl",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** Configure and create a lobby: pick the table shape and the timeouts, then create. */
@Composable
internal fun CreateLobbyScreen(
    onCreate: (mode: GameMode, turnTimeoutSeconds: Int, idleDisbandMinutes: Int) -> Unit,
    onBack: () -> Unit,
) {
    var mode by remember { mutableStateOf(GameMode.FOUR_PLAYER) }
    var turnTimeout by remember { mutableStateOf(TURN_TIMEOUT_OPTIONS[1]) }
    var idleMinutes by remember { mutableStateOf(IDLE_OPTIONS[2]) }
    OnlineScaffold(title = "Create a game", onBack = onBack) {
        Text("Table", style = MaterialTheme.typography.labelLarge)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GameMode.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        GameModeButton(mode = m, selected = m == mode, onClick = { mode = m }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Text("Turn time-out", style = MaterialTheme.typography.labelLarge)
        ChipRow(
            options = TURN_TIMEOUT_OPTIONS,
            selected = turnTimeout,
            label = { "${it}s" },
            onSelect = { turnTimeout = it },
        )
        Text("Disband if idle for", style = MaterialTheme.typography.labelLarge)
        ChipRow(
            options = IDLE_OPTIONS,
            selected = idleMinutes,
            label = { "${it}m" },
            onSelect = { idleMinutes = it },
        )
        Button(
            onClick = { onCreate(mode, turnTimeout, idleMinutes) },
            modifier = Modifier.fillMaxWidth().testTag("confirmCreate"),
        ) { Text("Create") }
    }
}

/** Join an existing lobby by its 4-character code. */
@Composable
internal fun JoinLobbyScreen(
    playerName: String,
    onSetPlayerName: (String) -> Unit,
    initialCode: String,
    onJoin: (name: String, code: String) -> Unit,
    onBack: () -> Unit,
) {
    // The name field is here too (not only on the entry screen) so a player who arrived via an
    // invite link — which skips the entry screen — can still set their name before joining.
    var name by remember { mutableStateOf(playerName) }
    var code by remember { mutableStateOf(initialCode.uppercase().take(CODE_LENGTH)) }
    val nameValid = Names.isValid(name)
    OnlineScaffold(title = "Join a game", onBack = onBack) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                if (Names.isValid(it)) onSetPlayerName(Names.normalize(it))
            },
            label = { Text("Your name") },
            singleLine = true,
            isError = name.isNotEmpty() && !nameValid,
            supportingText = {
                if (name.isNotEmpty() && !nameValid) Text("2–20 letters/digits; can't end with \"(bot)\"")
            },
            colors = onBackgroundFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("playerName"),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(CODE_LENGTH) },
            label = { Text("Game code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            colors = onBackgroundFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("joinCode"),
        )
        Button(
            onClick = { onJoin(name, code) },
            enabled = nameValid && code.length == CODE_LENGTH,
            modifier = Modifier.fillMaxWidth().testTag("confirmJoin"),
        ) { Text("Join") }
    }
}

/** A row of single-select chips over [options]; the selected one gets a filled, high-contrast look. */
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            OutlinedButton(
                onClick = { onSelect(option) },
                colors = if (isSelected) {
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = onBackground.copy(alpha = 0.18f),
                        contentColor = onBackground,
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors(contentColor = onBackground.copy(alpha = 0.7f))
                },
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    onBackground.copy(alpha = if (isSelected) 1f else 0.4f),
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(label(option), fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private const val CODE_LENGTH = 4
private val TURN_TIMEOUT_OPTIONS = listOf(30, 45, 60, 120)
private val IDLE_OPTIONS = listOf(5, 15, 30, 60)
