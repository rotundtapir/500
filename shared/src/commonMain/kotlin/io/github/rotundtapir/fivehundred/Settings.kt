// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import kotlinx.coroutines.flow.Flow

/** How quickly bot turns play out (the delay before each bot decision). */
enum class AnimationSpeed(val label: String, val botDelayMillis: Long) {
    SLOW("Slow", 1600),
    NORMAL("Normal", 800),
    FAST("Fast", 250),
    OFF("Off", 0);

    /** The next speed in the cycle Slow → Normal → Fast → Off → Slow. */
    fun next(): AnimationSpeed = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * Lenient parse for persisted values and test overrides (intent extras, URL parameters):
         * the matching entry, or null when [name] is unset or unrecognised.
         */
        fun fromName(name: String?): AnimationSpeed? = entries.find { it.name == name }
    }
}

/**
 * Storage key names, shared byte-for-byte by the DataStore (Android) and localStorage (web)
 * backends. Renaming one orphans every user's saved value for that setting on that platform.
 */
object SettingsKeys {
    const val ANIMATION_SPEED = "animation_speed"
    const val SORT_HAND_BY_DEFAULT = "sort_hand_by_default"
    const val MISERE_ENABLED = "misere_enabled"
    const val NO_TRUMPS_ENABLED = "no_trumps_enabled"
    const val HOLD_TRICKS = "hold_tricks"
    const val SOUND_VOLUME = "sound_volume"
    const val SERVER_URL = "server_url"
    const val PLAYER_NAME = "player_name"
}

/**
 * Each setting's value when nothing is stored — the single source for both platform backends and
 * for the UI's pre-load `collectAsState` initial values.
 */
object SettingsDefaults {
    val ANIMATION_SPEED = AnimationSpeed.NORMAL
    const val SORT_HAND_BY_DEFAULT = false
    const val MISERE_ENABLED = true
    const val NO_TRUMPS_ENABLED = true
    const val HOLD_TRICKS = false
    const val SOUND_VOLUME = 0.7f

    /** The official game server. Self-hosters / local testing point this elsewhere. */
    const val SERVER_URL = "wss://500.29022617.xyz"

    /** Empty until the player picks a name on the online entry screen. */
    const val PLAYER_NAME = ""
}

/**
 * Persisted user preferences. Backed per platform: Jetpack DataStore on Android
 * ([DataStoreSettingsRepository]), `localStorage` in the browser build. Both backends store under
 * [SettingsKeys] and fall back to [SettingsDefaults] when a value is unset.
 */
interface SettingsRepository {
    /** The persisted animation speed; [SettingsDefaults.ANIMATION_SPEED] when unset or unrecognised. */
    val animationSpeed: Flow<AnimationSpeed>

    suspend fun setAnimationSpeed(speed: AnimationSpeed)

    /** Whether new hands start sorted; [SettingsDefaults.SORT_HAND_BY_DEFAULT] (deal order) when unset. */
    val sortHandByDefault: Flow<Boolean>

    suspend fun setSortHandByDefault(value: Boolean)

    /** House rule: whether Misère / Open Misère may be bid. Applies to new games. */
    val misereEnabled: Flow<Boolean>

    suspend fun setMisereEnabled(value: Boolean)

    /** House rule: whether no-trump contracts may be bid. Applies to new games. */
    val noTrumpsEnabled: Flow<Boolean>

    suspend fun setNoTrumpsEnabled(value: Boolean)

    /** Whether completed tricks stay on the felt until tapped away. */
    val holdTricks: Flow<Boolean>

    suspend fun setHoldTricks(value: Boolean)

    /** Sound-effect volume, 0f (muted) to 1f; [SettingsDefaults.SOUND_VOLUME] when unset. */
    val soundVolume: Flow<Float>

    suspend fun setSoundVolume(value: Float)

    /** The online game server URL (`wss://…`); [SettingsDefaults.SERVER_URL] when unset. */
    val serverUrl: Flow<String>

    suspend fun setServerUrl(value: String)

    /** The player's chosen display name for online games; [SettingsDefaults.PLAYER_NAME] when unset. */
    val playerName: Flow<String>

    suspend fun setPlayerName(value: String)
}
