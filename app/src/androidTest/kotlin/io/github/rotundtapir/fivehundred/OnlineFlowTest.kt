// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.equalTo
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.net.InetSocketAddress
import java.net.Socket

/**
 * On-device tests of the online flow against a real, host-run game server (the Android answer to
 * the web's `online.spec.ts` — issue #10): entry → create lobby → the server's LobbyState renders →
 * Start fills empty seats with bots and the game reaches the human's bidding turn; plus the invite
 * link's *share* (send) side, which only exists on Android (`ACTION_SEND` via a chooser).
 *
 * Requires a server on the host: `DEV_MODE=true ./gradlew :server:run` (the emulator reaches it at
 * `ws://10.0.2.2:8080`). Without one the tests skip via [assumeTrue] rather than fail, so the rest
 * of the connected suite still runs on a plain emulator; CI starts the server before this suite.
 *
 * Name-ordered: the share test opens the system share sheet (system UI a Compose test can't close
 * reliably), so it must run after the game-flow test.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OnlineFlowTest {

    companion object {
        // The host machine as the emulator sees it.
        private const val SERVER_HOST = "10.0.2.2"
        private const val SERVER_PORT = 8080
        private const val SERVER_URL = "ws://$SERVER_HOST:$SERVER_PORT"
        private const val REACH_TIMEOUT_MS = 2_000
        private const val STEP_TIMEOUT_MS = 30_000L
    }

    @get:Rule
    val rule = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ANIMATION_SPEED, "OFF")
                .putExtra(MainActivity.EXTRA_SOUND_VOLUME, 0f)
                .putExtra(MainActivity.EXTRA_SERVER_URL, SERVER_URL)
                // Seeds the persisted display name (like the web's ?playerName=), so the online
                // screens prefill and the tests never type into the canvas.
                .putExtra(MainActivity.EXTRA_PLAYER_NAME, "Tester"),
        ),
        activityProvider = { scenarioRule ->
            var activity: MainActivity? = null
            scenarioRule.scenario.onActivity { activity = it }
            activity!!
        },
    )

    @Before
    fun requireLocalServer() {
        val reachable = runCatching {
            Socket().use { it.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT), REACH_TIMEOUT_MS) }
        }.isSuccess
        assumeTrue(
            "No game server at $SERVER_URL — start one on the host: DEV_MODE=true ./gradlew :server:run",
            reachable,
        )
    }

    private fun textExists(text: String): Boolean =
        rule.onAllNodes(
            SemanticsMatcher("has text '$text'") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()

    private fun waitForTag(tag: String) = rule.waitUntil(STEP_TIMEOUT_MS) {
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** Enter online mode and create a lobby, returning the join code the server assigned. */
    private fun createLobby(): String {
        rule.onNodeWithText("Play with friends").performClick()
        // Enabled only once the seeded name has landed in settings and prefilled the field.
        rule.waitUntil(STEP_TIMEOUT_MS) {
            rule.onAllNodes(hasTestTag("createLobby") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("createLobby").performClick()
        rule.onNodeWithTag("confirmCreate").performClick()
        // The server replied with a LobbyState: the 4-character join code is on screen.
        waitForTag("lobbyCode")
        return rule.onNodeWithTag("lobbyCode")
            .fetchSemanticsNode().config[SemanticsProperties.Text].first().text
    }

    /** The full happy path the web e2e can't reach (its canvas-click limitation stops at the lobby). */
    @Test
    fun test1_createLobby_startGame_reachesTheHumansBiddingTurn() {
        val code = createLobby()
        assertTrue("join code '$code' should be 4 characters", code.length == 4)
        rule.onNodeWithTag("startGame").performClick()
        // Three bot seats filled, the deal ran, and the auction reached the human.
        rule.waitUntil(STEP_TIMEOUT_MS) { textExists("Your bid:") }
    }

    /** The invite-link share action: the host taps share and Android gets an ACTION_SEND chooser. */
    @Test
    fun test2_shareInviteLink_firesASendChooserCarryingTheJoinUrl() {
        Intents.init()
        try {
            val code = createLobby()
            rule.onNodeWithTag("shareInvite").performClick()
            intended(
                allOf(
                    hasAction(Intent.ACTION_CHOOSER),
                    hasExtra(
                        equalTo(Intent.EXTRA_INTENT),
                        allOf(
                            hasAction(Intent.ACTION_SEND),
                            hasExtra(equalTo(Intent.EXTRA_TEXT), containsString("?joinCode=$code")),
                        ),
                    ),
                ),
            )
        } finally {
            Intents.release()
            // Close the system share sheet the tap opened so nothing lingers over later suites.
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
}
