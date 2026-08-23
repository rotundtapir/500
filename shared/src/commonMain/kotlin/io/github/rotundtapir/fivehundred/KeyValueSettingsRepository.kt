// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.ui.settings.AnimationSpeed
import io.github.rotundtapir.cardkit.ui.settings.BotSkill
import io.github.rotundtapir.cardkit.ui.settings.KeyValueStore
import io.github.rotundtapir.cardkit.ui.settings.booleanSetting
import io.github.rotundtapir.cardkit.ui.settings.enumSetting
import io.github.rotundtapir.cardkit.ui.settings.putEnum
import io.github.rotundtapir.cardkit.ui.settings.stringSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] over cardkit-ui's per-platform [KeyValueStore] seam: Preferences DataStore
 * on Android, the browser's localStorage on web. Both platform repositories delegate here, so
 * every setting's key ([SettingsKeys]), default ([SettingsDefaults]) and value handling (lenient
 * enum parse, volume clamp, blank-server fallback) is defined exactly once — and the stores keep
 * the wire encodings byte-identical to what shipped, so existing installs' settings survive.
 */
class KeyValueSettingsRepository(private val store: KeyValueStore) : SettingsRepository {

    override val animationSpeed: Flow<AnimationSpeed> =
        store.enumSetting(SettingsKeys.ANIMATION_SPEED, SettingsDefaults.ANIMATION_SPEED, AnimationSpeed::fromName)

    override suspend fun setAnimationSpeed(speed: AnimationSpeed) =
        store.putEnum(SettingsKeys.ANIMATION_SPEED, speed)

    override val sortHandByDefault: Flow<Boolean> =
        store.booleanSetting(SettingsKeys.SORT_HAND_BY_DEFAULT, SettingsDefaults.SORT_HAND_BY_DEFAULT)

    override suspend fun setSortHandByDefault(value: Boolean) =
        store.putBoolean(SettingsKeys.SORT_HAND_BY_DEFAULT, value)

    override val misereEnabled: Flow<Boolean> =
        store.booleanSetting(SettingsKeys.MISERE_ENABLED, SettingsDefaults.MISERE_ENABLED)

    override suspend fun setMisereEnabled(value: Boolean) =
        store.putBoolean(SettingsKeys.MISERE_ENABLED, value)

    override val noTrumpsEnabled: Flow<Boolean> =
        store.booleanSetting(SettingsKeys.NO_TRUMPS_ENABLED, SettingsDefaults.NO_TRUMPS_ENABLED)

    override suspend fun setNoTrumpsEnabled(value: Boolean) =
        store.putBoolean(SettingsKeys.NO_TRUMPS_ENABLED, value)

    override val holdTricks: Flow<Boolean> =
        store.booleanSetting(SettingsKeys.HOLD_TRICKS, SettingsDefaults.HOLD_TRICKS)

    override suspend fun setHoldTricks(value: Boolean) =
        store.putBoolean(SettingsKeys.HOLD_TRICKS, value)

    override val botSkill: Flow<BotSkill> =
        store.enumSetting(SettingsKeys.BOT_SKILL, SettingsDefaults.BOT_SKILL, BotSkill::fromName)

    override suspend fun setBotSkill(value: BotSkill) = store.putEnum(SettingsKeys.BOT_SKILL, value)

    override val soundVolume: Flow<Float> = store.float(SettingsKeys.SOUND_VOLUME)
        .map { (it ?: SettingsDefaults.SOUND_VOLUME).coerceIn(0f, 1f) }

    override suspend fun setSoundVolume(value: Float) =
        store.putFloat(SettingsKeys.SOUND_VOLUME, value.coerceIn(0f, 1f))

    override val narrationEnabled: Flow<Boolean> =
        store.booleanSetting(SettingsKeys.NARRATION_ENABLED, SettingsDefaults.NARRATION_ENABLED)

    override suspend fun setNarrationEnabled(value: Boolean) =
        store.putBoolean(SettingsKeys.NARRATION_ENABLED, value)

    override val serverUrl: Flow<String> = store.string(SettingsKeys.SERVER_URL)
        .map { stored -> stored?.takeIf { it.isNotBlank() } ?: SettingsDefaults.SERVER_URL }

    override suspend fun setServerUrl(value: String) =
        store.putString(SettingsKeys.SERVER_URL, value.trim())

    override val playerName: Flow<String> =
        store.stringSetting(SettingsKeys.PLAYER_NAME, SettingsDefaults.PLAYER_NAME)

    override suspend fun setPlayerName(value: String) = store.putString(SettingsKeys.PLAYER_NAME, value)

    override val cheatsUnlocked: Flow<Boolean> =
        store.booleanSetting(SettingsKeys.CHEATS_UNLOCKED, SettingsDefaults.CHEATS_UNLOCKED)

    override suspend fun setCheatsUnlocked(value: Boolean) =
        store.putBoolean(SettingsKeys.CHEATS_UNLOCKED, value)
}
