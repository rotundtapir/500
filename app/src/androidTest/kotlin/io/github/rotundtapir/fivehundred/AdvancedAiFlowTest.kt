// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device integration tests for the Advanced AI path: [MainActivity.EXTRA_BOT_SKILL] pins the
 * Monte-Carlo bot and [MainActivity.EXTRA_AI_BUDGET_MS] shrinks its search budgets to test speed,
 * so these run as fast as [GameFlowTest] while proving the advanced bots drive a real game through
 * the real ViewModel/driver wiring (off the main thread — the UI must stay live while they think).
 */
@RunWith(AndroidJUnit4::class)
class AdvancedAiFlowTest {

    companion object {
        private const val SEED = 42L
        private const val STEP_TIMEOUT_MS = 20_000L
    }

    @get:Rule
    val rule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SEED, SEED)
                .putExtra(MainActivity.EXTRA_ANIMATION_SPEED, "OFF")
                .putExtra(MainActivity.EXTRA_SOUND_VOLUME, 0f)
                .putExtra(MainActivity.EXTRA_BOT_SKILL, "ADVANCED")
                // 25ms per trick (bid 75ms, kitty 50ms): real searching, test-suite pacing.
                .putExtra(MainActivity.EXTRA_AI_BUDGET_MS, 25L)
        ),
        activityProvider = { scenarioRule ->
            var activity: MainActivity? = null
            scenarioRule.scenario.onActivity { activity = it }
            activity!!
        },
    )

    private val clickableCard = hasClickAction() and hasAnyDescendant(cardFace())

    private fun textExists(text: String, substring: Boolean = false): Boolean =
        rule.onAllNodes(
            SemanticsMatcher("has text '$text'") { node ->
                node.config.getOrNull(SemanticsProperties.Text)
                    ?.any { it.text == text || (substring && it.text.contains(text)) } == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

    private fun clickableCards() = rule.onAllNodes(clickableCard, useUnmergedTree = true)

    private fun startGame() {
        rule.onNodeWithText("Play with bots").performClick()
        rule.onNodeWithTag("startBotGame").performClick()
    }

    @Test
    fun advancedBots_driveARealGame_throughBidAndPlay() {
        startGame()
        // Reaching the human's bid prompt already means the advanced bots ahead of seat 0 searched
        // and bid without stalling the driver or freezing the UI thread.
        rule.waitUntil(STEP_TIMEOUT_MS) { textExists("Your bid:") }
        rule.onNodeWithTag("bid:Pass").performScrollTo().performClick()

        // Play through the human's first few turns: every prompt reached here required the three
        // advanced bots to produce legal moves through the real determinize-and-search path.
        var plays = 0
        val deadline = System.currentTimeMillis() + 60_000
        while (plays < 3 && System.currentTimeMillis() < deadline) {
            rule.waitUntil(STEP_TIMEOUT_MS) {
                textExists("Your turn — tap a card to play") ||
                    textExists("Your bid:") ||
                    textExists("(last:", substring = true) ||
                    textExists("You win!") || textExists("You lose")
            }
            when {
                textExists("Your turn — tap a card to play") -> {
                    val playable = clickableCards().fetchSemanticsNodes().size
                    assertTrue("at least one legal play", playable >= 1)
                    plays++
                    clickableCards()[0].performScrollTo().performClick()
                    rule.waitForIdle()
                }
                textExists("Your bid:") -> {
                    rule.onNodeWithTag("bid:Pass").performScrollTo().performClick()
                    rule.waitForIdle()
                }
                else -> return // hand ended early — the advanced bots completed it legally
            }
        }
        assertTrue("expected to reach at least one play turn", plays >= 1)
    }

    @Test
    fun advancedAiSwitch_enabledOnHome_disabledInGame() {
        // From home the switch is live (applies to new games)…
        rule.onNodeWithTag("settingsButton").performClick()
        rule.onNodeWithTag("advancedAi").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("Done").performClick()

        // …but in-game the bots are fixed, so the same dialog disables it.
        startGame()
        rule.waitUntil(STEP_TIMEOUT_MS) { textExists("Your bid:") }
        rule.onNodeWithTag("gameSettingsButton").performClick()
        rule.onNodeWithTag("advancedAi").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Done").performClick()
        assertTrue("dismissing settings must return to the table", textExists("Your bid:"))
    }
}
