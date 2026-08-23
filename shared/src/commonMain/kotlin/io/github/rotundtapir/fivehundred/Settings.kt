// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.ui.settings.AnimationSpeed
import io.github.rotundtapir.cardkit.ui.settings.BotSkill
import kotlinx.coroutines.flow.Flow

// The AnimationSpeed and BotSkill setting enums live in cardkit-ui
// (io.github.rotundtapir.cardkit.ui.settings); their entry names are frozen there — they are
// persisted verbatim under [SettingsKeys] and parsed from test overrides.

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
    const val BOT_SKILL = "bot_skill"
    const val SOUND_VOLUME = "sound_volume"
    const val NARRATION_ENABLED = "narration_enabled"
    const val SERVER_URL = "server_url"
    const val PLAYER_NAME = "player_name"
    const val CHEATS_UNLOCKED = "cheats_unlocked"
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
    val BOT_SKILL = BotSkill.STANDARD
    const val SOUND_VOLUME = 0.7f

    /** Tutorial voice narration — on by default; the home screen advertises it and offers the mute. */
    const val NARRATION_ENABLED = true

    /** The official game server. Self-hosters / local testing point this elsewhere. */
    const val SERVER_URL = "wss://500.29022617.xyz"

    /** Empty until the player picks a name on the online entry screen. */
    const val PLAYER_NAME = ""

    /** The developer-style cheats menu stays hidden until deliberately unlocked (see #51). */
    const val CHEATS_UNLOCKED = false
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

    /** Which bot AI new local games use ([SettingsDefaults.BOT_SKILL] when unset). Applies to new games. */
    val botSkill: Flow<BotSkill>

    suspend fun setBotSkill(value: BotSkill)

    /** Sound-effect volume, 0f (muted) to 1f; [SettingsDefaults.SOUND_VOLUME] when unset. */
    val soundVolume: Flow<Float>

    suspend fun setSoundVolume(value: Float)

    /** Whether the tutorial speaks its guidance aloud; [SettingsDefaults.NARRATION_ENABLED] when unset. */
    val narrationEnabled: Flow<Boolean>

    suspend fun setNarrationEnabled(value: Boolean)

    /** The online game server URL (`wss://…`); [SettingsDefaults.SERVER_URL] when unset. */
    val serverUrl: Flow<String>

    suspend fun setServerUrl(value: String)

    /** The player's chosen display name for online games; [SettingsDefaults.PLAYER_NAME] when unset. */
    val playerName: Flow<String>

    suspend fun setPlayerName(value: String)

    /**
     * Whether the hidden cheats menu has been unlocked (tapping the About dialog's version row, the
     * way Android unlocks developer options). Persisted so it survives a restart, and re-lockable
     * from the menu itself — an unlock a user cannot undo is a trap.
     *
     * Every cheat behind it is offline-only; see `ui/CheatsPanel.kt`.
     */
    val cheatsUnlocked: Flow<Boolean>

    suspend fun setCheatsUnlocked(value: Boolean)
}
