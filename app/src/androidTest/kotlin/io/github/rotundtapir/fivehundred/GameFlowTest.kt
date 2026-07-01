// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device integration tests: drive the real [MainActivity] — engine, bots, ViewModel, and Compose
 * UI wired together — through complete game flows.
 *
 * The game seed is pinned via [MainActivity.EXTRA_SEED], so every run deals the same cards and the
 * bots (seeded from the game seed) make the same decisions: failures reproduce exactly. Assertions
 * are still written against *rules invariants* (hand sizes, phase transitions, scoring happened)
 * rather than specific cards, so changing the seed only changes the path, not the expectations.
 */
@RunWith(AndroidJUnit4::class)
class GameFlowTest {

    companion object {
        private const val SEED = 42L
        private const val STEP_TIMEOUT_MS = 20_000L
    }

    @get:Rule
    val rule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SEED, SEED)
                // Disable bot pacing so tests aren't slowed by presentation delays.
                .putExtra(MainActivity.EXTRA_ANIMATION_SPEED, "OFF")
        ),
        activityProvider = { scenarioRule ->
            var activity: MainActivity? = null
            scenarioRule.scenario.onActivity { activity = it }
            activity!!
        },
    )

    // ---------------------------------------------------------------------------------------------
    // Semantics helpers
    // ---------------------------------------------------------------------------------------------

    /** Matches nodes whose testTag starts with [prefix] (cards are tagged `card:<label>`). */
    private fun hasTestTagPrefix(prefix: String) =
        SemanticsMatcher("testTag starts with '$prefix'") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    /** A face-up card the human can currently tap (its wrapper is clickable only when legal). */
    private val clickableCard = hasClickAction() and hasAnyDescendant(hasTestTagPrefix("card:"))

    private fun textExists(text: String, substring: Boolean = false): Boolean =
        rule.onAllNodes(
            SemanticsMatcher("has text '$text'") { node ->
                node.config.getOrNull(SemanticsProperties.Text)
                    ?.any { it.text == text || (substring && it.text.contains(text)) } == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntil(STEP_TIMEOUT_MS) { textExists(text, substring) }

    private fun cardsOnScreen(): Int =
        rule.onAllNodes(hasTestTagPrefix("card:"), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun clickableCards() = rule.onAllNodes(clickableCard, useUnmergedTree = true)

    private fun startGame() {
        rule.onNodeWithText("New Game").performClick()
    }

    /** Waits until it is the human's bidding turn and the bid panel is up. */
    private fun waitForBidPanel() = waitForText("Your bid:")

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun homeScreen_showsTitleAndActions() {
        rule.onNodeWithText("500").assertIsDisplayed()
        rule.onNodeWithText("New Game").assertIsDisplayed()
        // FOSS flavor: a donation link, never an ads purchase.
        rule.onNodeWithText("Support development").assertIsDisplayed()
    }

    @Test
    fun newGame_dealsTenCards_andBiddingReachesHuman() {
        startGame()
        waitForBidPanel()

        // Passing is always legal, and the full 10-card hand is visible while bidding.
        rule.onNodeWithTag("bid:Pass").assertIsDisplayed()
        assertEquals("hand should hold 10 cards during bidding", 10, cardsOnScreen())

        // While bidding, cards must not be tappable (no play is legal yet).
        assertEquals(0, clickableCards().fetchSemanticsNodes().size)
    }

    @Test
    fun openMisereBid_winsContract_andKittyExchangeWorks() {
        startGame()
        waitForBidPanel()

        // Open Misère is the top of the bid ladder — nothing can outbid it, so the human must win
        // the contract and receive the 3-card kitty.
        rule.onNodeWithTag("bid:Open Misère").performScrollTo().performClick()
        waitForText("Discard 3 cards", substring = true)

        assertEquals("hand + kitty during the exchange", 13, cardsOnScreen())
        rule.onNodeWithTag("discardButton").assertIsNotEnabled()

        // Select any three cards; the discard button arms only at exactly three. The hand is wider
        // than the screen, so bring each card into view first.
        repeat(3) { i ->
            clickableCards()[i].performScrollTo().performClick()
            rule.waitForIdle()
        }
        waitForText("(3/3 selected)", substring = true)
        rule.onNodeWithTag("discardButton").assertIsEnabled().performClick()

        // Exchange done: back to 10 cards, the contract is ours, and in Misère the declarer's
        // partner sits out.
        rule.waitUntil(STEP_TIMEOUT_MS) { !textExists("Discard 3 cards", substring = true) }
        waitForText("Contract: You · Open Misère", substring = true)
        waitForText("(sitting out)")
        assertEquals("hand back to 10 after the exchange", 10, cardsOnScreen())
    }

    @Test
    fun passedContract_playsAFullHand_andScoringHappens() {
        startGame()
        playOneHandToCompletion()

        // Either the next hand's bidding shows the previous result, or the ±500 threshold ended the
        // whole game. Both prove deal → bid → play → score ran end to end.
        assertTrue(
            "expected a completed hand (result dialog, last-hand line, or game-over dialog)",
            textExists("Contract made!") || textExists("Contract failed") ||
                textExists("(last:", substring = true) ||
                textExists("You win!") || textExists("You lose"),
        )

        // If the hand-result dialog is up, it must dismiss and reveal the next hand's bidding
        // with the previous result summarised on the contract line.
        if (textExists("Contract made!") || textExists("Contract failed")) {
            rule.onNodeWithTag("handResultContinue").performClick()
            rule.waitUntil(STEP_TIMEOUT_MS) {
                textExists("(last:", substring = true) ||
                    textExists("You win!") || textExists("You lose")
            }
        }
    }

    @Test
    fun homeScreen_hasAnimationSpeedToggle() {
        rule.onNodeWithTag("animationSpeed").assertIsDisplayed()
    }

    @Test
    fun handSortToggle_keepsAllTenCards() {
        startGame()
        waitForBidPanel()
        assertEquals(10, cardsOnScreen())
        rule.onNodeWithTag("sortToggle").performClick()
        rule.waitForIdle()
        assertEquals("toggling sort must not add or drop cards", 10, cardsOnScreen())
    }

    @Test
    fun menuButton_returnsToHomeScreen() {
        startGame()
        waitForBidPanel()
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("New Game").assertIsDisplayed()
    }

    @Test
    fun menuThenNewGame_dealsAFreshHand() {
        startGame()
        waitForBidPanel()
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("New Game").performClick()
        waitForBidPanel()
        assertEquals("fresh hand after restarting from the menu", 10, cardsOnScreen())
        rule.onNodeWithText("Us: 0").assertIsDisplayed()
    }

    @Test
    fun inProgressGame_survivesActivityRecreation() {
        startGame()
        waitForBidPanel()

        // Simulates rotation / theme change / process-driven recreation: the game runs in the
        // ViewModel and the in-game flag is saveable, so the table must still be showing.
        rule.activityRule.scenario.recreate()

        rule.waitUntil(STEP_TIMEOUT_MS) { textExists("Menu") }
        assertTrue("game screen should survive recreation", textExists("Your bid:"))
        assertEquals(10, cardsOnScreen())
    }

    @Test
    fun discardSelection_isCappedAtKittySize() {
        startGame()
        waitForBidPanel()
        rule.onNodeWithTag("bid:Open Misère").performScrollTo().performClick()
        waitForText("Discard 3 cards", substring = true)

        // Try to select four cards: the selection must stop at three.
        repeat(4) { i ->
            clickableCards()[i].performScrollTo().performClick()
            rule.waitForIdle()
        }
        waitForText("(3/3 selected)", substring = true)
        rule.onNodeWithTag("discardButton").assertIsEnabled()
    }

    @Test
    fun whenItIsYourTurn_someCardsArePlayable_neverMoreThanHandSize() {
        startGame()
        waitForBidPanel()
        rule.onNodeWithTag("bid:Pass").performClick()

        // Play through the human's first few turns of the hand, checking the legality invariant
        // each time: at least one card must be playable, never more than are held.
        var checks = 0
        val deadline = System.currentTimeMillis() + 60_000
        while (checks < 3 && System.currentTimeMillis() < deadline) {
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
                    assertTrue("no more legal plays than cards held", playable <= 10)
                    checks++
                    clickableCards()[0].performScrollTo().performClick()
                    rule.waitForIdle()
                }
                textExists("Your bid:") -> {
                    rule.onNodeWithTag("bid:Pass").performScrollTo().performClick()
                    rule.waitForIdle()
                }
                else -> return // hand ended early — invariant held for every turn we saw
            }
        }
        assertTrue("expected to reach at least one play turn", checks >= 1)
    }

    // ---------------------------------------------------------------------------------------------
    // Generic hand driver
    // ---------------------------------------------------------------------------------------------

    /**
     * Plays generically — always Pass at bidding, always the first legal card — until the hand is
     * scored (next bidding shows a "(last: …)" result) or the game ends. Rules invariants, not card
     * choices, are what the caller asserts on.
     */
    private fun playOneHandToCompletion() {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (textExists("(last:", substring = true) ||
                textExists("You win!") || textExists("You lose")
            ) return

            // Wait until the UI needs the human (or the hand finishes in the background).
            rule.waitUntil(STEP_TIMEOUT_MS) {
                textExists("Your bid:") ||
                    textExists("Discard 3 cards", substring = true) ||
                    textExists("Your turn — tap a card to play") ||
                    textExists("Contract made!") || textExists("Contract failed") ||
                    textExists("(last:", substring = true) ||
                    textExists("You win!") || textExists("You lose")
            }

            when {
                // A hand just finished — the result dialog blocks input until dismissed.
                textExists("Contract made!") || textExists("Contract failed") -> return
                textExists("Your bid:") -> {
                    if (textExists("(last:", substring = true)) return // previous hand scored
                    rule.onNodeWithTag("bid:Pass").performScrollTo().performClick()
                    rule.waitForIdle()
                }
                textExists("Discard 3 cards", substring = true) -> {
                    repeat(3) { i ->
                        clickableCards()[i].performScrollTo().performClick()
                        rule.waitForIdle()
                    }
                    rule.onNodeWithTag("discardButton").performClick()
                    rule.waitForIdle()
                }
                textExists("Your turn — tap a card to play") -> {
                    val playable = clickableCards()
                    if (playable.fetchSemanticsNodes().isNotEmpty()) {
                        playable[0].performScrollTo().performClick()
                        rule.waitForIdle()
                    }
                }
            }
        }
        throw AssertionError("hand did not complete within 120s")
    }
}
