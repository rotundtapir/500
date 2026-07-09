// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.rotundtapir.cardkit.ui.SoundEffect
import io.github.rotundtapir.cardkit.ui.rememberSoundManager
import io.github.rotundtapir.fivehundred.engine.PlayerView

/**
 * State-driven sound triggers: observes [view] transitions (against the previously seen view) and
 * plays the matching cardkit [SoundEffect]. Wire it next to the game screen with the current
 * [PlayerView] and the persisted volume setting; a null [view] is a no-op.
 *
 * Triggers:
 *  - the current trick grew → [SoundEffect.CARD_PLACE]
 *  - the trick number increased → [SoundEffect.TRICK_TAKEN]
 *  - a (new) hand result appeared → [SoundEffect.SCORE]
 *
 * Deal/shuffle sounds are not derived here — they fire from the dealing animation via
 * `DealAnimationState.soundHook`.
 */
@Composable
fun rememberGameSoundEffects(view: PlayerView?, volume: Float): (SoundEffect) -> Unit {
    val manager = rememberSoundManager(volume)

    var previous by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(view) {
        val prev = previous
        previous = view
        soundEffectsFor(prev, view).forEach(manager::play)
    }

    // Shared with imperative call sites (the dealing animation's soundHook).
    return remember(manager) { { effect: SoundEffect -> manager.play(effect) } }
}

/**
 * The effects a [prev]-to-[next] view transition should trigger, as pure logic so the trigger
 * rules are unit-testable. A null on either side (before the first view) triggers nothing.
 */
internal fun soundEffectsFor(prev: PlayerView?, next: PlayerView?): List<SoundEffect> = buildList {
    if (prev == null || next == null) return@buildList
    if (next.currentTrick.size > prev.currentTrick.size) add(SoundEffect.CARD_PLACE)
    if (next.trickNumber > prev.trickNumber) add(SoundEffect.TRICK_TAKEN)
    // By count, not by value: consecutive hands can score structurally identically, and a value
    // comparison would silently swallow the second hand's score sound.
    if (next.handResults.size > prev.handResults.size) add(SoundEffect.SCORE)
}
