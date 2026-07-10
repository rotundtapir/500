// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.ui.theme.CardkitTheme
import io.github.rotundtapir.fivehundred.online.JoinLink

class MainActivity : ComponentActivity() {
    companion object {
        /** Intent extra overriding the game seed — set by instrumentation tests for reproducibility. */
        const val EXTRA_SEED = "io.github.rotundtapir.fivehundred.SEED"

        /**
         * Intent extra (an [AnimationSpeed] name) overriding the persisted animation speed — set by
         * instrumentation tests to run without bot delays.
         */
        const val EXTRA_ANIMATION_SPEED = "io.github.rotundtapir.fivehundred.ANIMATION_SPEED"

        /** Intent extra (Float) overriding the persisted sound volume — tests pass 0f. */
        const val EXTRA_SOUND_VOLUME = "io.github.rotundtapir.fivehundred.SOUND_VOLUME"

        /**
         * Intent extra overriding the online server URL — for manual/opt-in online testing against a
         * host-run server (the emulator reaches it at `ws://10.0.2.2:8080`).
         */
        const val EXTRA_SERVER_URL = "io.github.rotundtapir.fivehundred.SERVER_URL"

        /**
         * Intent extra seeding the online display name (the web `?playerName=` mirror) — set by
         * instrumentation tests so the online screens prefill without canvas text entry.
         */
        const val EXTRA_PLAYER_NAME = "io.github.rotundtapir.fivehundred.PLAYER_NAME"
    }

    private lateinit var monetization: Monetization

    // The join code from the invite link that (re)launched us, observed by the composition so a link
    // tapped while the app is already open (onNewIntent) navigates into the join screen too.
    private var deepLinkJoinCode by mutableStateOf<String?>(null)

    /** The `?joinCode=` from an App Links VIEW intent (`https://…/500/?joinCode=12AB`), or null. */
    private fun joinCodeFromIntent(intent: Intent?): String? =
        intent?.data?.getQueryParameter(JoinLink.PARAM)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkJoinCode = joinCodeFromIntent(intent)
    }

    private fun newGameSeed(): Long =
        if (intent?.hasExtra(EXTRA_SEED) == true) intent.getLongExtra(EXTRA_SEED, 0)
        else System.currentTimeMillis()

    /** The sound volume forced by the launching intent, or null to use the persisted setting. */
    private fun soundVolumeOverride(): Float? =
        if (intent?.hasExtra(EXTRA_SOUND_VOLUME) == true) intent.getFloatExtra(EXTRA_SOUND_VOLUME, 0f) else null

    /** The animation speed forced by the launching intent, or null to use the persisted setting. */
    private fun animationSpeedOverride(): AnimationSpeed? =
        AnimationSpeed.fromName(intent?.getStringExtra(EXTRA_ANIMATION_SPEED))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monetization = MonetizationProvider.create(this)
        deepLinkJoinCode = joinCodeFromIntent(intent)
        setContent {
            CardkitTheme {
                FiveHundredApp(
                    monetization = monetization,
                    settings = remember { DataStoreSettingsRepository(applicationContext) },
                    appConfig = AppConfig(
                        feedbackUri = BuildConfig.FEEDBACK_URI,
                        version = BuildConfig.VERSION_NAME,
                        platform = io.github.rotundtapir.fivehundred.net.Platform.ANDROID,
                    ),
                    nextSeed = ::newGameSeed,
                    linkSharer = remember { AndroidLinkSharer(this) },
                    joinCodeOverride = deepLinkJoinCode,
                    animationSpeedOverride = animationSpeedOverride(),
                    soundVolumeOverride = soundVolumeOverride(),
                    serverUrlOverride = intent?.getStringExtra(EXTRA_SERVER_URL),
                    playerNameOverride = intent?.getStringExtra(EXTRA_PLAYER_NAME),
                )
            }
        }
    }

    override fun onDestroy() {
        monetization.dispose()
        super.onDestroy()
    }
}
