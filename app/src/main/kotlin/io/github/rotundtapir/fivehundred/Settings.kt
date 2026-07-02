// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How quickly bot turns play out (the delay before each bot decision). */
enum class AnimationSpeed(val label: String, val botDelayMillis: Long) {
    SLOW("Slow", 1600),
    NORMAL("Normal", 800),
    FAST("Fast", 250),
    OFF("Off", 0);

    /** The next speed in the cycle Slow → Normal → Fast → Off → Slow. */
    fun next(): AnimationSpeed = entries[(ordinal + 1) % entries.size]
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Persisted user preferences, backed by Jetpack DataStore. */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    /** The persisted animation speed; [AnimationSpeed.NORMAL] when unset or unrecognised. */
    val animationSpeed: Flow<AnimationSpeed> = dataStore.data.map { preferences ->
        preferences[ANIMATION_SPEED_KEY]
            ?.let { stored -> AnimationSpeed.entries.find { it.name == stored } }
            ?: AnimationSpeed.NORMAL
    }

    suspend fun setAnimationSpeed(speed: AnimationSpeed) {
        dataStore.edit { preferences -> preferences[ANIMATION_SPEED_KEY] = speed.name }
    }

    /** Whether new hands start sorted; false (deal order) when unset. */
    val sortHandByDefault: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SORT_HAND_BY_DEFAULT_KEY] ?: false
    }

    suspend fun setSortHandByDefault(value: Boolean) {
        dataStore.edit { preferences -> preferences[SORT_HAND_BY_DEFAULT_KEY] = value }
    }

    /** House rule: whether Misère / Open Misère may be bid. Applies to new games; true when unset. */
    val misereEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[MISERE_ENABLED_KEY] ?: true
    }

    suspend fun setMisereEnabled(value: Boolean) {
        dataStore.edit { preferences -> preferences[MISERE_ENABLED_KEY] = value }
    }

    /** House rule: whether no-trump contracts may be bid. Applies to new games; true when unset. */
    val noTrumpsEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[NO_TRUMPS_ENABLED_KEY] ?: true
    }

    suspend fun setNoTrumpsEnabled(value: Boolean) {
        dataStore.edit { preferences -> preferences[NO_TRUMPS_ENABLED_KEY] = value }
    }

    private companion object {
        val ANIMATION_SPEED_KEY = stringPreferencesKey("animation_speed")
        val SORT_HAND_BY_DEFAULT_KEY = booleanPreferencesKey("sort_hand_by_default")
        val MISERE_ENABLED_KEY = booleanPreferencesKey("misere_enabled")
        val NO_TRUMPS_ENABLED_KEY = booleanPreferencesKey("no_trumps_enabled")
    }
}
