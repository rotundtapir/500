// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.web

import io.github.rotundtapir.cardkit.ui.settings.LocalStorageKeyValueStore
import io.github.rotundtapir.fivehundred.KeyValueSettingsRepository
import io.github.rotundtapir.fivehundred.SettingsRepository

/**
 * [SettingsRepository] backed by the browser's `localStorage` — [KeyValueSettingsRepository] over
 * cardkit-ui's [LocalStorageKeyValueStore]. Each setting is one `settings.<key>` entry under the
 * shared SettingsKeys names, string-encoded exactly as before the migration (and byte-identical
 * in spirit to the Android DataStore implementation, per that object's contract), so returning
 * visitors keep their saved settings.
 */
class LocalStorageSettingsRepository :
    SettingsRepository by KeyValueSettingsRepository(LocalStorageKeyValueStore("settings."))
