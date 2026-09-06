// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/** Step timeout shared by the flow tests: generous, since a bot turn at OFF is still real work. */
const val STEP_TIMEOUT_MS = 20_000L

/** A face-up card the human can currently tap (its wrapper is clickable only when legal). */
val clickableCard: SemanticsMatcher = hasClickAction() and hasAnyDescendant(cardFace())

/** Whether any node shows [text] (exactly, or as a substring). Unmerged tree, so dialogs count. */
fun ComposeTestRule.textExists(text: String, substring: Boolean = false): Boolean =
    onAllNodes(
        SemanticsMatcher("has text '$text'") { node ->
            node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == text || (substring && it.text.contains(text)) } == true
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.tagExists(tag: String): Boolean =
    onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.waitForText(text: String, substring: Boolean = false) =
    waitUntil(STEP_TIMEOUT_MS) { textExists(text, substring) }

fun ComposeTestRule.clickableCards(): SemanticsNodeInteractionCollection =
    onAllNodes(clickableCard, useUnmergedTree = true)

/** Home -> bot-setup (optionally picking a non-default table) -> Play. */
fun ComposeTestRule.startBotGame(modeTag: String? = null) {
    onNodeWithText("Play with bots").performClick()
    modeTag?.let { onNodeWithTag(it).performClick() }
    onNodeWithTag("startBotGame").performClick()
}

/** Selects any three cards during the kitty exchange and confirms the discard. */
fun ComposeTestRule.discardThree() {
    waitForText("Discard 3 cards", substring = true)
    repeat(3) { i ->
        clickableCards()[i].performScrollTo().performClick()
        waitForIdle()
    }
    onNodeWithTag("discardButton").performClick()
    waitForIdle()
}
