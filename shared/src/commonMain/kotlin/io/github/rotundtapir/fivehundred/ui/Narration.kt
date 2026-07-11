// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import io.github.rotundtapir.fivehundred.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The tutorial's voice narration, bundled for the surfaces that show tutorial text: the user's
 * on/off toggle (which drives the ♪ indicators), whether playback is actually audible (the toggle
 * AND master volume — at volume 0 no audio object is ever created, which the instrumented
 * `-no-audio` emulator depends on), and the platform player.
 */
@Immutable
class NarrationState(
    val enabled: Boolean,
    val audible: Boolean,
    val onToggle: () -> Unit,
    internal val player: NarrationPlayer,
)

/**
 * Speaks the pre-generated clip for [displayText] whenever it changes (and narration is audible);
 * muting stops the current clip immediately. Texts without a clip (dynamic fallbacks) stay silent.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun NarrateEffect(narration: NarrationState?, displayText: String) {
    if (narration == null) return
    LaunchedEffect(displayText, narration.audible) {
        if (!narration.audible) return@LaunchedEffect
        val id = narrationIdFor(displayText) ?: return@LaunchedEffect
        narration.player.play(Res.getUri("files/narration/$id.mp3"))
    }
    LaunchedEffect(narration.audible) {
        if (!narration.audible) narration.player.stop()
    }
    // Leaving the narrated surface — cancelling the primer, exiting the tutorial, the epilogue
    // closing — must silence the voice with it, not let the clip finish over the next screen.
    DisposableEffect(narration.player) {
        onDispose { narration.player.stop() }
    }
}

/**
 * The narration mute toggle: "♪ on" / "♪ off". [compact] drops the word "narration" for the game
 * screen's top bar and the pager's button row. Plain glyph + text so it renders on the wasm
 * canvas (no combining characters, no emoji outside the bundled symbol subset).
 */
@Composable
internal fun NarrationToggle(
    narration: NarrationState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onBackground,
) {
    val label = when {
        compact && narration.enabled -> "♪ on"
        compact -> "♪ off"
        narration.enabled -> "♪ Narration on"
        else -> "♪ Narration off"
    }
    TextButton(
        onClick = narration.onToggle,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (narration.enabled) tint else tint.copy(alpha = 0.6f),
        ),
        modifier = modifier.testTag("narrationToggle"),
    ) { Text(label) }
}
