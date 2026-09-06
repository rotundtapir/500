// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens that have to stay usable in a SHORT window — a phone in landscape (~360–410dp tall),
 * a flip's cover, a browser on a phone. The manifest's portrait lock keeps Android phones out of
 * that shape for now, but the web build and large screens are not locked, and the lock is meant to
 * go once the layout is safe; these tests force landscape through [ActivityInfo] to prove it is.
 *
 * Each failure here was real: the game-over sheet's "Back to menu" was clipped below the window
 * (and back did nothing) so the only way out was killing the app; the home stack lost About off
 * the bottom; Settings clipped mid-list; the tutorial primer's Next was unreachable; and a
 * rotation mid-hand resurrected the previous hand's dismissed result dialog as a modal.
 */
@RunWith(AndroidJUnit4::class)
class LandscapeFlowTest {

    companion object {
        private const val SEED = 42L
    }

    @get:Rule
    val rule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SEED, SEED)
                .putExtra(MainActivity.EXTRA_ANIMATION_SPEED, "OFF")
                .putExtra(MainActivity.EXTRA_SOUND_VOLUME, 0f)
        ),
        activityProvider = { scenarioRule ->
            var activity: MainActivity? = null
            scenarioRule.scenario.onActivity { activity = it }
            activity!!
        },
    )

    /**
     * Turns the (recreated) activity to landscape and waits for the home screen to come back in
     * the new shape. The runtime request overrides the manifest's `userPortrait`.
     */
    private fun rotateToLandscape() {
        rule.waitForText("Play with bots")
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        rule.waitUntil(STEP_TIMEOUT_MS) {
            var landscape = false
            rule.activityRule.scenario.onActivity {
                landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            landscape && rule.textExists("Play with bots")
        }
    }

    /** Plays the human's cards (any legal one) until a hand result or the game end shows. */
    private fun playUntilHandResultOrGameEnd() {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            rule.waitUntil(STEP_TIMEOUT_MS) {
                rule.textExists("Your turn — tap a card to play") || rule.tagExists("handResultContinue") ||
                    rule.textExists("You win!") || rule.textExists("You lose")
            }
            if (rule.tagExists("handResultContinue") || rule.textExists("You win!") || rule.textExists("You lose")) return
            val playable = rule.clickableCards()
            if (playable.fetchSemanticsNodes().isNotEmpty()) {
                playable[0].performScrollTo().performClick()
                rule.waitForIdle()
            }
        }
        throw AssertionError("no hand result or game end within 120s")
    }

    @Test
    fun landscape_homeScreen_aboutIsReachable() {
        rotateToLandscape()
        // The stack is taller than the window: About is below the fold and must scroll into view.
        rule.onNodeWithTag("aboutButton").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(STEP_TIMEOUT_MS) { rule.tagExists("aboutDialog") }
    }

    @Test
    fun landscape_settingsDialog_lowerRowsAreReachable() {
        rotateToLandscape()
        rule.onNodeWithTag("settingsButton").performClick()
        rule.waitUntil(STEP_TIMEOUT_MS) { rule.tagExists("volumeSlider") }
        // Everything from "Sound volume" down was clipped: the list must scroll to its last rows.
        rule.onNodeWithTag("volumeSlider").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("helpButton").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun landscape_tutorialPrimer_nextIsReachable() {
        rotateToLandscape()
        rule.onNodeWithTag("walkthroughButton").performScrollTo().performClick()
        rule.waitUntil(STEP_TIMEOUT_MS) { rule.tagExists("tutorialIntroNext") }
        rule.onNodeWithTag("tutorialIntroNext").assertIsDisplayed()
    }

    @Test
    fun landscape_gameOver_backToMenuIsReachable_andBackDismisses() {
        rotateToLandscape()
        rule.startBotGame()
        rule.waitForText("Your bid:")
        // Open Misère ends the game in ONE hand either way: made it is +500 (win), failed it is
        // −500 (lose) — so the game-over sheet is reachable without playing a whole match.
        rule.onNodeWithTag("bid:Open Misère").performScrollTo().performClick()
        rule.discardThree()
        playUntilHandResultOrGameEnd()
        rule.onNodeWithTag("handResultContinue").assertIsDisplayed().performClick()
        rule.waitUntil(STEP_TIMEOUT_MS) { rule.textExists("You win!") || rule.textExists("You lose") }

        // The sheet is taller than a landscape window; the action must be pinned on screen.
        rule.onNodeWithTag("backToMenu").assertIsDisplayed()
        // System back is the other way out — it used to do nothing here. (Espresso's pressBack
        // reaches the dialog's own window; the activity's dispatcher would bypass it.)
        Espresso.pressBack()
        rule.waitForText("Play with bots")
    }

    @Test
    fun dismissedHandResult_staysDismissed_acrossRecreation() {
        rule.startBotGame()
        rule.waitForText("Your bid:")
        rule.onNodeWithTag("bid:Open Misère").performScrollTo().performClick()
        rule.discardThree()
        playUntilHandResultOrGameEnd()
        rule.onNodeWithTag("handResultContinue").performClick()
        rule.waitUntil(STEP_TIMEOUT_MS) { rule.textExists("You win!") || rule.textExists("You lose") }

        // Rotation / theme change: the acknowledgement must survive, or the dismissed breakdown
        // comes back on top of the sheet (and mid-match, as a modal over the live hand).
        rule.activityRule.scenario.recreate()
        rule.waitUntil(STEP_TIMEOUT_MS) { rule.textExists("You win!") || rule.textExists("You lose") }
        assertFalse("dismissed hand result must not reappear", rule.tagExists("handResultContinue"))
        assertTrue(rule.tagExists("backToMenu"))
    }
}
