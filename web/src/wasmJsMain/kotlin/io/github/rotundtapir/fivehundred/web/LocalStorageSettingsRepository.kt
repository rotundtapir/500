// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.web

import io.github.rotundtapir.fivehundred.AnimationSpeed
import io.github.rotundtapir.fivehundred.SettingsDefaults
import io.github.rotundtapir.fivehundred.SettingsKeys
import io.github.rotundtapir.fivehundred.SettingsRepository
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [SettingsRepository] backed by the browser's `localStorage`. Each setting is one
 * `settings.<key>` entry under the shared [SettingsKeys] names (byte-identical to the Android
 * DataStore implementation, per that object's contract).
 * Values live in a per-setting [MutableStateFlow] seeded from storage, so reads are synchronous
 * and the Flow surface behaves like DataStore's.
 */
class LocalStorageSettingsRepository : SettingsRepository {

    private fun stored(key: String): String? = localStorage.getItem("settings.$key")

    private fun store(key: String, value: String) {
        localStorage.setItem("settings.$key", value)
    }

    private val animationSpeedFlow = MutableStateFlow(
        AnimationSpeed.fromName(stored(SettingsKeys.ANIMATION_SPEED)) ?: SettingsDefaults.ANIMATION_SPEED,
    )
    override val animationSpeed: Flow<AnimationSpeed> = animationSpeedFlow

    override suspend fun setAnimationSpeed(speed: AnimationSpeed) {
        store(SettingsKeys.ANIMATION_SPEED, speed.name)
        animationSpeedFlow.value = speed
    }

    private val sortHandByDefaultFlow = MutableStateFlow(stored(SettingsKeys.SORT_HAND_BY_DEFAULT)?.toBoolean() ?: SettingsDefaults.SORT_HAND_BY_DEFAULT)
    override val sortHandByDefault: Flow<Boolean> = sortHandByDefaultFlow

    override suspend fun setSortHandByDefault(value: Boolean) {
        store(SettingsKeys.SORT_HAND_BY_DEFAULT, value.toString())
        sortHandByDefaultFlow.value = value
    }

    private val misereEnabledFlow = MutableStateFlow(stored(SettingsKeys.MISERE_ENABLED)?.toBoolean() ?: SettingsDefaults.MISERE_ENABLED)
    override val misereEnabled: Flow<Boolean> = misereEnabledFlow

    override suspend fun setMisereEnabled(value: Boolean) {
        store(SettingsKeys.MISERE_ENABLED, value.toString())
        misereEnabledFlow.value = value
    }

    private val noTrumpsEnabledFlow = MutableStateFlow(stored(SettingsKeys.NO_TRUMPS_ENABLED)?.toBoolean() ?: SettingsDefaults.NO_TRUMPS_ENABLED)
    override val noTrumpsEnabled: Flow<Boolean> = noTrumpsEnabledFlow

    override suspend fun setNoTrumpsEnabled(value: Boolean) {
        store(SettingsKeys.NO_TRUMPS_ENABLED, value.toString())
        noTrumpsEnabledFlow.value = value
    }

    private val holdTricksFlow = MutableStateFlow(stored(SettingsKeys.HOLD_TRICKS)?.toBoolean() ?: SettingsDefaults.HOLD_TRICKS)
    override val holdTricks: Flow<Boolean> = holdTricksFlow

    override suspend fun setHoldTricks(value: Boolean) {
        store(SettingsKeys.HOLD_TRICKS, value.toString())
        holdTricksFlow.value = value
    }

    private val soundVolumeFlow = MutableStateFlow((stored(SettingsKeys.SOUND_VOLUME)?.toFloatOrNull() ?: SettingsDefaults.SOUND_VOLUME).coerceIn(0f, 1f))
    override val soundVolume: Flow<Float> = soundVolumeFlow

    override suspend fun setSoundVolume(value: Float) {
        val coerced = value.coerceIn(0f, 1f)
        store(SettingsKeys.SOUND_VOLUME, coerced.toString())
        soundVolumeFlow.value = coerced
    }

    private val serverUrlFlow = MutableStateFlow(
        stored(SettingsKeys.SERVER_URL)?.takeIf { it.isNotBlank() } ?: SettingsDefaults.SERVER_URL,
    )
    override val serverUrl: Flow<String> = serverUrlFlow

    override suspend fun setServerUrl(value: String) {
        val trimmed = value.trim()
        store(SettingsKeys.SERVER_URL, trimmed)
        serverUrlFlow.value = trimmed.ifBlank { SettingsDefaults.SERVER_URL }
    }

    private val playerNameFlow = MutableStateFlow(stored(SettingsKeys.PLAYER_NAME) ?: SettingsDefaults.PLAYER_NAME)
    override val playerName: Flow<String> = playerNameFlow

    override suspend fun setPlayerName(value: String) {
        store(SettingsKeys.PLAYER_NAME, value)
        playerNameFlow.value = value
    }
}
