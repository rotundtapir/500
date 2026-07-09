// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.runtime.Immutable
import io.github.rotundtapir.fivehundred.AnimationSpeed

/**
 * The settings dialog's plumbing, bundled: each setting's current value plus its write-through
 * callback. Built once in `FiveHundredApp` and passed as a unit to [HomeScreen], [GameScreen] and
 * [SettingsDialog], so adding a setting touches one construction site instead of a parameter list
 * per screen.
 */
@Immutable
data class SettingsControls(
    val animationSpeed: AnimationSpeed,
    val onCycleAnimationSpeed: () -> Unit,
    val sortByDefault: Boolean,
    val onSetSortByDefault: (Boolean) -> Unit,
    val holdTricks: Boolean,
    val onSetHoldTricks: (Boolean) -> Unit,
    val soundVolume: Float,
    val onSetSoundVolume: (Float) -> Unit,
    val misereEnabled: Boolean,
    val onSetMisereEnabled: (Boolean) -> Unit,
    val noTrumpsEnabled: Boolean,
    val onSetNoTrumpsEnabled: (Boolean) -> Unit,
    val serverUrl: String,
    val onSetServerUrl: (String) -> Unit,
    val playerName: String,
    val onSetPlayerName: (String) -> Unit,
)
