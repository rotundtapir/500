// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** How quickly bot turns play out (the delay before each bot decision). */
enum class AnimationSpeed(val label: String, val botDelayMillis: Long) {
    NORMAL("Normal", 800),
    FAST("Fast", 250),
    OFF("Off", 0);

    /** The next speed in the cycle Normal → Fast → Off → Normal. */
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

    private companion object {
        val ANIMATION_SPEED_KEY = stringPreferencesKey("animation_speed")
    }
}
