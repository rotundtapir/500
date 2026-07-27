// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.rotundtapir.cardkit.ui.settings.DataStoreKeyValueStore
import io.github.rotundtapir.cardkit.ui.settings.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] backed by Jetpack Preferences DataStore — the Android implementation, as
 * [KeyValueSettingsRepository] over cardkit-ui's [DataStoreKeyValueStore]. That store writes the
 * same "settings" preferences file under the same typed keys as the previous direct DataStore
 * repository, so existing installs' saved settings survive the migration byte-for-byte.
 *
 * The internal constructor takes a [DataStore] directly so unit tests can supply one backed by a
 * temp file; production code uses the [Context] constructor.
 */
class DataStoreSettingsRepository private constructor(store: KeyValueStore) :
    SettingsRepository by KeyValueSettingsRepository(store) {

    constructor(context: Context) : this(DataStoreKeyValueStore(context, "settings"))

    internal constructor(dataStore: DataStore<Preferences>) : this(PreferencesKeyValueStore(dataStore))
}

/**
 * [KeyValueStore] over an externally supplied [DataStore] — the test seam behind
 * [DataStoreSettingsRepository]'s internal constructor (cardkit's own DataStore-injecting
 * constructor is internal to cardkit-ui). Encodings match [DataStoreKeyValueStore]: typed
 * string/boolean/float preference keys.
 */
private class PreferencesKeyValueStore(private val dataStore: DataStore<Preferences>) : KeyValueStore {

    private fun <T> read(key: Preferences.Key<T>): Flow<T?> = dataStore.data.map { it[key] }

    private suspend fun <T> write(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    override fun string(key: String): Flow<String?> = read(stringPreferencesKey(key))

    override suspend fun putString(key: String, value: String) = write(stringPreferencesKey(key), value)

    override fun boolean(key: String): Flow<Boolean?> = read(booleanPreferencesKey(key))

    override suspend fun putBoolean(key: String, value: Boolean) = write(booleanPreferencesKey(key), value)

    override fun float(key: String): Flow<Float?> = read(floatPreferencesKey(key))

    override suspend fun putFloat(key: String, value: Float) = write(floatPreferencesKey(key), value)
}
