// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [AnimationSpeed] cycle and lenient parsing. */
class AnimationSpeedTest {

    @Test
    fun `next cycles Slow to Normal to Fast to Off and back`() {
        assertEquals(AnimationSpeed.NORMAL, AnimationSpeed.SLOW.next())
        assertEquals(AnimationSpeed.FAST, AnimationSpeed.NORMAL.next())
        assertEquals(AnimationSpeed.OFF, AnimationSpeed.FAST.next())
        assertEquals(AnimationSpeed.SLOW, AnimationSpeed.OFF.next())
    }

    @Test
    fun `fromName parses known names and rejects the rest`() {
        assertEquals(AnimationSpeed.OFF, AnimationSpeed.fromName("OFF"))
        assertEquals(AnimationSpeed.SLOW, AnimationSpeed.fromName("SLOW"))
        assertEquals(null, AnimationSpeed.fromName("off")) // exact name match only
        assertEquals(null, AnimationSpeed.fromName("bogus"))
        assertEquals(null, AnimationSpeed.fromName(null))
    }
}

/**
 * [DataStoreSettingsRepository] against a real temp-file [DataStore] via its internal
 * DataStore-injecting constructor — no Android runtime, no Robolectric.
 */
class DataStoreSettingsRepositoryTest {

    private val tempFiles = mutableListOf<File>()

    private fun newStore(): DataStore<Preferences> {
        val file = File.createTempFile("settings-${UUID.randomUUID()}", ".preferences_pb").also { tempFiles += it }
        file.delete() // DataStore wants to own creation
        return PreferenceDataStoreFactory.create { file }
    }

    @AfterTest
    fun cleanUp() {
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `defaults hold when nothing is stored`() = runTest {
        val repo = DataStoreSettingsRepository(newStore())
        assertEquals(SettingsDefaults.ANIMATION_SPEED, repo.animationSpeed.first())
        assertEquals(SettingsDefaults.SORT_HAND_BY_DEFAULT, repo.sortHandByDefault.first())
        assertEquals(SettingsDefaults.MISERE_ENABLED, repo.misereEnabled.first())
        assertEquals(SettingsDefaults.NO_TRUMPS_ENABLED, repo.noTrumpsEnabled.first())
        assertEquals(SettingsDefaults.HOLD_TRICKS, repo.holdTricks.first())
        assertEquals(SettingsDefaults.SOUND_VOLUME, repo.soundVolume.first())
        assertEquals(SettingsDefaults.SERVER_URL, repo.serverUrl.first())
        assertEquals(SettingsDefaults.PLAYER_NAME, repo.playerName.first())
    }

    @Test
    fun `every setting round-trips through storage`() = runTest {
        val store = newStore()
        val repo = DataStoreSettingsRepository(store)
        repo.setAnimationSpeed(AnimationSpeed.FAST)
        repo.setSortHandByDefault(true)
        repo.setMisereEnabled(false)
        repo.setNoTrumpsEnabled(false)
        repo.setHoldTricks(true)
        repo.setSoundVolume(0.3f)
        repo.setServerUrl("ws://localhost:8080")
        repo.setPlayerName("Alice")

        // A fresh repository over the same store reads back the persisted values.
        val reopened = DataStoreSettingsRepository(store)
        assertEquals(AnimationSpeed.FAST, reopened.animationSpeed.first())
        assertEquals(true, reopened.sortHandByDefault.first())
        assertEquals(false, reopened.misereEnabled.first())
        assertEquals(false, reopened.noTrumpsEnabled.first())
        assertEquals(true, reopened.holdTricks.first())
        assertEquals(0.3f, reopened.soundVolume.first())
        assertEquals("ws://localhost:8080", reopened.serverUrl.first())
        assertEquals("Alice", reopened.playerName.first())
    }

    @Test
    fun `a blank stored server url falls back to the default`() = runTest {
        val store = newStore()
        store.edit { it[stringPreferencesKey(SettingsKeys.SERVER_URL)] = "   " }
        assertEquals(SettingsDefaults.SERVER_URL, DataStoreSettingsRepository(store).serverUrl.first())
    }

    @Test
    fun `an unrecognised stored animation speed falls back to the default`() = runTest {
        val store = newStore()
        store.edit { it[stringPreferencesKey(SettingsKeys.ANIMATION_SPEED)] = "TURBO" }
        assertEquals(SettingsDefaults.ANIMATION_SPEED, DataStoreSettingsRepository(store).animationSpeed.first())
    }

    @Test
    fun `sound volume is coerced into 0 to 1 on read and on write`() = runTest {
        val store = newStore()
        // Out-of-range value already on disk is clamped on read.
        store.edit { it[floatPreferencesKey(SettingsKeys.SOUND_VOLUME)] = 5f }
        val repo = DataStoreSettingsRepository(store)
        assertEquals(1f, repo.soundVolume.first())
        // And a written out-of-range value is clamped before it is stored.
        repo.setSoundVolume(-2f)
        assertEquals(0f, DataStoreSettingsRepository(store).soundVolume.first())
    }
}
