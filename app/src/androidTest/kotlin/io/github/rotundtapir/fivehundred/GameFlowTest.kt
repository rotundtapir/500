// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rotundtapir.fivehundred.engine.label
import io.github.rotundtapir.fivehundred.ui.TutorialStep
import io.github.rotundtapir.fivehundred.ui.tutorialSteps
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
                // Silence sounds: native audio playback on the -no-audio emulator can crash the
                // instrumented process; at 0f the SoundPool is never even created.
                .putExtra(MainActivity.EXTRA_SOUND_VOLUME, 0f)
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
        rule.onNodeWithTag("settingsButton").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------------------------
    // Interactive tutorial ("How to play"): a scripted hand on TUTORIAL_SEED where only the
    // recommended action is enabled at each step. See ui/Tutorial.kt for the script.
    // ---------------------------------------------------------------------------------------------

    /** Taps "How to play" and confirms the intro, landing in the scripted tutorial hand. */
    private fun startTutorial() {
        rule.onNodeWithTag("walkthroughButton").performClick()
        waitForText("Start")
        rule.onNodeWithTag("tutorialStart").performClick()
    }

    private fun nodesWithTag(tag: String) =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun tutorial_showsBidAdvice_andEnablesOnlyTheScriptedBid() {
        startTutorial()
        waitForBidPanel()

        // The guidance panel shows the first step's advice (the scripted bid, with the reason).
        val bidStep = tutorialSteps.first() as TutorialStep.BidStep
        rule.waitUntil(STEP_TIMEOUT_MS) { textExists(bidStep.advice) }
        rule.onNodeWithTag("tutorialAdvice").assertIsDisplayed()

        // Only the scripted bid is tappable; everything else — even Pass — is disabled.
        rule.onNodeWithTag("bid:${bidStep.bid.label}").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("bid:Pass").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun tutorial_takingTheScriptedBid_advancesToTheDiscardStep() {
        startTutorial()
        waitForBidPanel()
        val bidStep = tutorialSteps[0] as TutorialStep.BidStep
        val discardStep = tutorialSteps[1] as TutorialStep.DiscardStep
        rule.waitUntil(STEP_TIMEOUT_MS) { textExists(bidStep.advice) }

        rule.onNodeWithTag("bid:${bidStep.bid.label}").performScrollTo().performClick()

        // The advice changes to the kitty-exchange step once the auction resolves.
        rule.waitUntil(STEP_TIMEOUT_MS) { textExists(discardStep.advice) }
        waitForText("Discard 3 cards", substring = true)
    }

    /**
     * The whole feature end to end: plays the entire scripted hand through the constrained actions
     * (at every step exactly one action is enabled) and asserts the completion dialog appears and
     * exits to home.
     */
    @Test
    fun tutorial_playsTheFullScriptedHand_toTheCompletionDialog() {
        startTutorial()

        val bidStep = tutorialSteps.first() as TutorialStep.BidStep
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline && nodesWithTag("tutorialComplete").isEmpty()) {
            rule.waitUntil(STEP_TIMEOUT_MS) {
                nodesWithTag("tutorialComplete").isNotEmpty() ||
                    nodesWithTag("tutorialEpilogueNext").isNotEmpty() ||
                    textExists("Your bid:") ||
                    textExists("Discard 3 cards", substring = true) ||
                    textExists("Your turn — tap a card to play") ||
                    textExists("Contract made!")
            }
            when {
                nodesWithTag("tutorialComplete").isNotEmpty() -> break
                // The misère / no-trumps epilogue pages between the hand result and completion.
                nodesWithTag("tutorialEpilogueNext").isNotEmpty() -> {
                    rule.onNodeWithTag("tutorialEpilogueNext").performClick()
                    rule.waitForIdle()
                }
                textExists("Contract made!") -> {
                    rule.onNodeWithTag("handResultContinue").performClick()
                    rule.waitForIdle()
                }
                textExists("Your bid:") -> {
                    rule.onNodeWithTag("bid:${bidStep.bid.label}").performScrollTo().performClick()
                    rule.waitForIdle()
                }
                textExists("Discard 3 cards", substring = true) -> {
                    // Only the three scripted discards are selectable.
                    repeat(3) { i ->
                        clickableCards()[i].performScrollTo().performClick()
                        rule.waitForIdle()
                    }
                    waitForText("(3/3 selected)", substring = true)
                    rule.onNodeWithTag("discardButton").assertIsEnabled().performClick()
                    rule.waitForIdle()
                }
                textExists("Your turn — tap a card to play") -> {
                    // Exactly one card — the scripted one — is playable.
                    val playable = clickableCards()
                    if (playable.fetchSemanticsNodes().size == 1) {
                        playable[0].performScrollTo().performClick()
                        rule.waitForIdle()
                    }
                }
            }
        }

        rule.onNodeWithTag("tutorialComplete").assertIsDisplayed()
        rule.onNodeWithTag("tutorialCompleteContinue").performClick()
        rule.onNodeWithText("New Game").assertIsDisplayed()
    }

    @Test
    fun helpRules_openFromSettings_andDocumentTheBowers() {
        rule.onNodeWithTag("settingsButton").performClick()
        rule.onNodeWithTag("helpButton").performClick()
        waitForText("Rules of 500")
        assertTrue(textExists("bower", substring = true))
        assertTrue("scoring table must show the 10NT value", textExists("520", substring = true))
        rule.onNodeWithTag("rulesClose").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("New Game").assertIsDisplayed()
    }

    @Test
    fun settingsDialog_hasSupportAndAcknowledgments() {
        rule.onNodeWithTag("settingsButton").performClick()
        // FOSS flavor: a donation link, never an ads purchase.
        rule.onNodeWithText("Support development").assertIsDisplayed()
        // Feedback goes to the issue tracker (FOSS) / mailto (Play) — just assert it's offered.
        rule.onNodeWithTag("feedbackButton").assertIsDisplayed()
        rule.onNodeWithTag("acknowledgments").performClick()
        // The card artist must be credited.
        waitForText("Byron Knoll", substring = true)
        rule.onNodeWithTag("acknowledgmentsClose").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("New Game").assertIsDisplayed()
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
        // The animation-speed control lives in the settings dialog behind the home screen's cog.
        rule.onNodeWithTag("settingsButton").performClick()
        rule.onNodeWithTag("animationSpeed").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("New Game").assertIsDisplayed()
    }

    /** How many nodes currently show exactly [text] (e.g. counting "(partner)" markers). */
    private fun textCount(text: String): Int =
        rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun twoPlayerGame_reachesBidding() {
        rule.onNodeWithTag("mode:2p").performClick()
        startGame()
        waitForBidPanel()
        assertEquals("2-player deal still gives the human 10 cards", 10, cardsOnScreen())
        // Head-to-head: no seat shares the human's team, so no partner marker anywhere.
        assertTrue("no partner marker expected in a 2-player game", !textExists("(partner)"))
    }

    @Test
    fun sixPlayerGame_reachesBidding() {
        rule.onNodeWithTag("mode:6p2t").performClick()
        startGame()
        waitForBidPanel()
        // Two teams of three: seats 2 and 4 share the human's team, so the opponents row must mark
        // exactly two partners.
        rule.waitUntil(STEP_TIMEOUT_MS) { textExists("(partner)") }
        assertEquals("two teams of three give the human two partners", 2, textCount("(partner)"))
        assertEquals("6-player deal still gives the human 10 cards", 10, cardsOnScreen())
    }

    @Test
    fun threeTeamsGame_reachesBidding_withExactlyOnePartner() {
        rule.onNodeWithTag("mode:6p3t").performClick()
        startGame()
        waitForBidPanel()
        // Three teams of two, partners opposite: only seat 3 shares the human's team.
        rule.waitUntil(STEP_TIMEOUT_MS) { textExists("(partner)") }
        assertEquals("three teams of two give the human exactly one partner", 1, textCount("(partner)"))
        assertEquals("6-player deal still gives the human 10 cards", 10, cardsOnScreen())
        // The score bar lists all three teams: "Us" plus two opposing pairs joined with "&".
        assertTrue("three-team score bar shows Us: 0", textExists("Us: 0", substring = true))
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
    fun menuButton_confirmsThenReturnsToHomeScreen() {
        startGame()
        waitForBidPanel()
        rule.onNodeWithText("Menu").performClick()
        waitForText("Leave game?")
        rule.onNodeWithTag("confirmLeave").performClick()
        rule.onNodeWithText("New Game").assertIsDisplayed()
    }

    @Test
    fun menuCancel_staysInGame() {
        startGame()
        waitForBidPanel()
        rule.onNodeWithText("Menu").performClick()
        waitForText("Leave game?")
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(STEP_TIMEOUT_MS) { !textExists("Leave game?") }
        assertTrue("cancelling the leave dialog must keep the game up", textExists("Your bid:"))
        assertTrue("home screen must not be shown after Cancel", !textExists("New Game"))
    }

    @Test
    fun menuThenNewGame_dealsAFreshHand() {
        startGame()
        waitForBidPanel()
        rule.onNodeWithText("Menu").performClick()
        waitForText("Leave game?")
        rule.onNodeWithTag("confirmLeave").performClick()
        rule.onNodeWithText("New Game").performClick()
        waitForBidPanel()
        assertEquals("fresh hand after restarting from the menu", 10, cardsOnScreen())
        rule.onNodeWithText("Us: 0").assertIsDisplayed()
    }

    /** Whether the Switch tagged [tag] currently reads as on (null if absent). */
    private fun switchIsOn(tag: String): Boolean? =
        nodesWithTag(tag).firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ToggleableState)
            ?.let { it == ToggleableState.On }

    @Test
    fun inGameSettingsCog_opensDialog_andDisablesHouseRules() {
        // From home the house-rule switches are live…
        rule.onNodeWithTag("settingsButton").performClick()
        rule.onNodeWithTag("misereEnabled").assertIsEnabled()
        rule.onNodeWithTag("noTrumpsEnabled").assertIsEnabled()
        rule.onNodeWithText("Done").performClick()

        // …but in-game they only apply to new games, so the same dialog disables them.
        startGame()
        waitForBidPanel()
        rule.onNodeWithTag("gameSettingsButton").performClick()
        rule.onNodeWithTag("misereEnabled").assertIsNotEnabled()
        rule.onNodeWithTag("noTrumpsEnabled").assertIsNotEnabled()
        rule.onNodeWithTag("holdTricks").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertTrue("dismissing settings must return to the table", textExists("Your bid:"))
    }

    @Test
    fun inGameSettings_holdTricksSwitch_toggles() {
        startGame()
        waitForBidPanel()
        rule.onNodeWithTag("gameSettingsButton").performClick()
        val initiallyOn = switchIsOn("holdTricks") == true
        // The click round-trips through DataStore before the switch recomposes — wait, don't assert.
        rule.onNodeWithTag("holdTricks").performClick()
        rule.waitUntil(STEP_TIMEOUT_MS) { switchIsOn("holdTricks") == !initiallyOn }
        // Toggle back so the persisted setting is unchanged for the other tests.
        rule.onNodeWithTag("holdTricks").performClick()
        rule.waitUntil(STEP_TIMEOUT_MS) { switchIsOn("holdTricks") == initiallyOn }
        rule.onNodeWithText("Done").performClick()
        assertTrue(textExists("Your bid:"))
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

    /**
     * Plays whole hands until the match ends: the final hand's result dialog must appear FIRST
     * (before any game-over dialog), and dismissing it reveals the game-over score sheet with a
     * row per scored hand and the exit button.
     */
    @Test
    fun gameEnd_showsFinalHandBreakdown_thenScoreSheet() {
        startGame()
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            playUntilHandResultOrGameEnd()
            if (textExists("Contract made!") || textExists("Contract failed")) {
                // Every hand — including the last — shows its breakdown before anything else.
                assertTrue(
                    "the game-over dialog must wait for the hand result to be dismissed",
                    !textExists("You win!") && !textExists("You lose"),
                )
                rule.onNodeWithTag("handResultContinue").performClick()
                rule.waitForIdle()
            }
            if (textExists("You win!") || textExists("You lose")) {
                // The score sheet tallies the hands played and offers the way out.
                rule.waitUntil(STEP_TIMEOUT_MS) { textExists("Hand") }
                rule.onNodeWithTag("backToMenu").performClick()
                rule.waitUntil(STEP_TIMEOUT_MS) { textExists("New Game") }
                return
            }
        }
        throw AssertionError("game did not end within 300s")
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

    /**
     * Like [playOneHandToCompletion], but keeps going across hands: only returns when a hand-result
     * dialog is showing or the game is over. ("(last: …)" is visible during the NEXT hand's bidding,
     * so it can't be used as a stop condition here.)
     */
    private fun playUntilHandResultOrGameEnd() {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            rule.waitUntil(STEP_TIMEOUT_MS) {
                textExists("Your bid:") ||
                    textExists("Discard 3 cards", substring = true) ||
                    textExists("Your turn — tap a card to play") ||
                    textExists("Contract made!") || textExists("Contract failed") ||
                    textExists("You win!") || textExists("You lose")
            }
            when {
                // Check dialogs first: "Your bid:" can already exist behind the result dialog.
                textExists("Contract made!") || textExists("Contract failed") -> return
                textExists("You win!") || textExists("You lose") -> return
                textExists("Your bid:") -> {
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
        throw AssertionError("no hand result or game end within 120s")
    }
}
