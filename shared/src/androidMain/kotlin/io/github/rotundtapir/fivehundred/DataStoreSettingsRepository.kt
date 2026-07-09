// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * [SettingsRepository] backed by Jetpack DataStore — the Android implementation.
 *
 * The primary constructor takes the [DataStore] directly so unit tests can supply one backed by a
 * temp file; production code uses the [Context] constructor, which binds the app's store.
 */
class DataStoreSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    override val animationSpeed: Flow<AnimationSpeed> = dataStore.data.map { preferences ->
        AnimationSpeed.fromName(preferences[ANIMATION_SPEED_KEY]) ?: SettingsDefaults.ANIMATION_SPEED
    }

    override suspend fun setAnimationSpeed(speed: AnimationSpeed) {
        dataStore.edit { preferences -> preferences[ANIMATION_SPEED_KEY] = speed.name }
    }

    override val sortHandByDefault: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SORT_HAND_BY_DEFAULT_KEY] ?: SettingsDefaults.SORT_HAND_BY_DEFAULT
    }

    override suspend fun setSortHandByDefault(value: Boolean) {
        dataStore.edit { preferences -> preferences[SORT_HAND_BY_DEFAULT_KEY] = value }
    }

    override val misereEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[MISERE_ENABLED_KEY] ?: SettingsDefaults.MISERE_ENABLED
    }

    override suspend fun setMisereEnabled(value: Boolean) {
        dataStore.edit { preferences -> preferences[MISERE_ENABLED_KEY] = value }
    }

    override val noTrumpsEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[NO_TRUMPS_ENABLED_KEY] ?: SettingsDefaults.NO_TRUMPS_ENABLED
    }

    override suspend fun setNoTrumpsEnabled(value: Boolean) {
        dataStore.edit { preferences -> preferences[NO_TRUMPS_ENABLED_KEY] = value }
    }

    override val holdTricks: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[HOLD_TRICKS_KEY] ?: SettingsDefaults.HOLD_TRICKS
    }

    override suspend fun setHoldTricks(value: Boolean) {
        dataStore.edit { preferences -> preferences[HOLD_TRICKS_KEY] = value }
    }

    override val soundVolume: Flow<Float> = dataStore.data.map { preferences ->
        (preferences[SOUND_VOLUME_KEY] ?: SettingsDefaults.SOUND_VOLUME).coerceIn(0f, 1f)
    }

    override suspend fun setSoundVolume(value: Float) {
        dataStore.edit { preferences -> preferences[SOUND_VOLUME_KEY] = value.coerceIn(0f, 1f) }
    }

    override val serverUrl: Flow<String> = dataStore.data.map { preferences ->
        preferences[SERVER_URL_KEY]?.takeIf { it.isNotBlank() } ?: SettingsDefaults.SERVER_URL
    }

    override suspend fun setServerUrl(value: String) {
        dataStore.edit { preferences -> preferences[SERVER_URL_KEY] = value.trim() }
    }

    override val playerName: Flow<String> = dataStore.data.map { preferences ->
        preferences[PLAYER_NAME_KEY] ?: SettingsDefaults.PLAYER_NAME
    }

    override suspend fun setPlayerName(value: String) {
        dataStore.edit { preferences -> preferences[PLAYER_NAME_KEY] = value }
    }

    private companion object {
        val ANIMATION_SPEED_KEY = stringPreferencesKey(SettingsKeys.ANIMATION_SPEED)
        val SORT_HAND_BY_DEFAULT_KEY = booleanPreferencesKey(SettingsKeys.SORT_HAND_BY_DEFAULT)
        val MISERE_ENABLED_KEY = booleanPreferencesKey(SettingsKeys.MISERE_ENABLED)
        val NO_TRUMPS_ENABLED_KEY = booleanPreferencesKey(SettingsKeys.NO_TRUMPS_ENABLED)
        val HOLD_TRICKS_KEY = booleanPreferencesKey(SettingsKeys.HOLD_TRICKS)
        val SOUND_VOLUME_KEY = floatPreferencesKey(SettingsKeys.SOUND_VOLUME)
        val SERVER_URL_KEY = stringPreferencesKey(SettingsKeys.SERVER_URL)
        val PLAYER_NAME_KEY = stringPreferencesKey(SettingsKeys.PLAYER_NAME)
    }
}
