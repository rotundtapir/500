// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Intent
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for #40: a hand dealt with animations OFF must survive turning them back on mid-hand.
 *
 * Deliberately does NOT pass [MainActivity.EXTRA_ANIMATION_SPEED]: that override wins over the
 * persisted setting for the activity's lifetime, so a suite running under it can flip the settings
 * dialog all it likes without the game ever seeing a speed change — exactly the gap that let #40
 * ship despite a 22-test connected suite. This test persists OFF through the real settings dialog,
 * the same path as the user in the report.
 */
@RunWith(AndroidJUnit4::class)
class AnimationSpeedChangeTest {

    @get:Rule
    val rule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SEED, 42L)
                // Volume stays pinned to 0: the SoundPool must never be created on the -no-audio
                // emulator. The speed extra is the one deliberately absent.
                .putExtra(MainActivity.EXTRA_SOUND_VOLUME, 0f),
        ),
        activityProvider = { scenarioRule ->
            var activity: MainActivity? = null
            scenarioRule.scenario.onActivity { activity = it }
            activity!!
        },
    )

    private fun cardsOnScreen(): Int =
        rule.onAllNodes(cardFace(), useUnmergedTree = true).fetchSemanticsNodes().size

    /**
     * Cycle the settings dialog's speed button until its label reads "Off" (cycle order
     * Slow → Normal → Fast → Off; the label is the only "Off" text in the dialog).
     */
    private fun cycleSpeedToOff() {
        repeat(4) {
            if (rule.onAllNodesWithText("Off").fetchSemanticsNodes().isNotEmpty()) return
            rule.onNodeWithTag("animationSpeed").performClick()
            rule.waitForIdle()
        }
        error("animationSpeed never reached 'Off'")
    }

    @Test
    fun handSurvivesTurningAnimationsOnMidHand() {
        // Persist OFF via the real settings dialog (no override in play).
        rule.onNodeWithTag("settingsButton").performClick()
        cycleSpeedToOff()
        rule.onNodeWithText("Done").performClick()

        // Start a bots game: at OFF the deal is skipped and the hand appears instantly.
        rule.onNodeWithText("Play with bots").performClick()
        rule.onNodeWithTag("startBotGame").performClick()
        rule.waitUntil(20_000L) { cardsOnScreen() >= 10 }
        val before = cardsOnScreen()
        assertTrue("expected a dealt hand before the speed change", before >= 10)

        // Mid-hand, turn animations back on (Off → Slow is one tap in the cycle).
        rule.onNodeWithTag("gameSettingsButton").performClick()
        rule.onNodeWithTag("animationSpeed").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()

        assertEquals("hand must not blank when animations turn on mid-hand (#40)", before, cardsOnScreen())
    }
}
