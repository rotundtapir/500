// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.ui.SoundEffect
import io.github.rotundtapir.fivehundred.AnimationSpeed
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlinx.coroutines.flow.first

@Composable
fun GameScreen(
    view: PlayerView,
    botNames: Map<Seat, String>,
    settings: SettingsControls,
    monetization: Monetization,
    onBid: (Bid) -> Unit,
    onDiscard: (List<Card>) -> Unit,
    onPlay: (Card) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    tutorial: TutorialScriptState? = null,
    onResultDismiss: (Int) -> Unit = {},
    onDealAnimationFinish: (Int) -> Unit = {},
    onTrickAcknowledge: (Int, Int) -> Unit = { _, _ -> },
    soundHook: ((SoundEffect) -> Unit)? = null,
    // Online games override the leave-confirm body — leaving hands the seat to a bot, not a loss.
    leaveConfirmText: String? = null,
) {
    val animationSpeed = settings.animationSpeed
    // Captured by the deal LaunchedEffect below; rememberUpdatedState so a recomposition that
    // changes the callback identity doesn't leave the running effect holding a stale one.
    val currentOnDealAnimationFinish by rememberUpdatedState(onDealAnimationFinish)
    var sortHand by rememberSaveable { mutableStateOf(settings.sortByDefault) }
    var showSettings by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    // Set once the tutorial's scripted hand has been scored and its result dialog dismissed.
    var tutorialComplete by rememberSaveable { mutableStateOf(false) }
    // Highest hand number whose result dialog has been dismissed — the shuffle/deal animation of
    // the NEXT hand waits for this, so nothing moves behind the dialog while the player reads it.
    var resultAckedHand by remember { mutableIntStateOf(0) }
    // Whether the FINAL hand's result dialog has been dismissed. resultAckedHand can't tell: between
    // hands the dialog shows under the NEXT hand's number, so by game end it already reads current.
    // Keyed on winner so it resets if this composable survives into another game.
    var finalResultAcked by remember(view.winner) { mutableStateOf(false) }

    // Dealing animation: on each new hand (unless animations are OFF) fly card backs one at a time
    // from a centre deck to each seat's pile / the kitty in 500's 3-4-3 packet order, then flip the
    // human's face-down row face up. While it runs the ActionArea is hidden so the player can't bid
    // mid-deal. Tests run at OFF, where this is skipped entirely (dealState.stage stays DONE).
    // lastAnimatedHand is saveable so recreation doesn't replay the deal.
    val dealState = remember { DealAnimationState() }
    dealState.soundHook = soundHook
    // Screen rects of the tutorial's interaction targets (bid button, cards, felt), for the bubble.
    val tutorialAnchors = if (tutorial != null) remember { TutorialAnchors() } else null
    var lastAnimatedHand by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(view.handNumber) {
        if (animationSpeed == AnimationSpeed.OFF) return@LaunchedEffect
        if (view.handNumber == lastAnimatedHand) return@LaunchedEffect
        lastAnimatedHand = view.handNumber
        // Only animate a genuine hand start (empty auction and no cards played yet). A view first
        // seen mid-hand — an online (re)connection snapshot — must not replay the deal; just release
        // the pacing signal so play proceeds.
        val handStart = view.phase == Phase.BIDDING && view.biddingHistory.isEmpty() && view.currentTrick.isEmpty()
        if (!handStart) {
            currentOnDealAnimationFinish(view.handNumber)
            return@LaunchedEffect
        }
        // Hold the shuffle until the previous hand's result dialog is dismissed.
        if (view.lastHandResult != null && view.winner == null) {
            snapshotFlow { resultAckedHand }.first { it >= view.handNumber }
        }
        runDealAnimation(dealState, view.playerCount, view.dealer, animationSpeed)
        // Release the first bidder: the ViewModel waits on this signal, not a timer, so slow
        // devices can't start the auction mid-deal.
        currentOnDealAnimationFinish(view.handNumber)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { dealState.overlayOrigin = it.positionInRoot() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 12.dp),
            ) {
                ScoreBar(
                    view = view,
                    botNames = botNames,
                    onOpenSettings = { showSettings = true },
                    onMenu = { showLeaveConfirm = true },
                )
                ContractLine(view, botNames)
                Spacer(Modifier.height(12.dp))
                OpponentsRow(view, botNames, dealState)
                ExposedDeclarerHand(view, botNames)
                TrickArea(
                    view = view,
                    botNames = botNames,
                    animationSpeed = animationSpeed,
                    dealState = dealState,
                    modifier = Modifier
                        .weight(1f)
                        .tutorialTarget(tutorialAnchors, "trick"),
                    holdTricks = settings.holdTricks,
                    // The tutorial always holds completed tricks so the bubble can explain each
                    // outcome (still inert at OFF, like all pacing).
                    forceHold = tutorial != null,
                    onTrickAcknowledge = onTrickAcknowledge,
                )
                if (dealState.dealing) {
                    DealingHandRow(
                        cards = if (sortHand) {
                            remember(view.hand, view.trump) { sortedForDisplay(view.hand, view.trump) }
                        } else view.hand,
                        state = dealState,
                        humanSeat = view.seat,
                        timings = dealTimings(animationSpeed),
                    )
                } else {
                    ActionArea(
                        view = view,
                        botNames = botNames,
                        sortHand = sortHand,
                        onToggleSort = { sortHand = !sortHand },
                        onBid = onBid,
                        onDiscard = onDiscard,
                        onPlay = onPlay,
                        tutorial = tutorial,
                        targets = tutorialAnchors,
                        peekDiscardHand = tutorial != null && animationSpeed != AnimationSpeed.OFF,
                    )
                }
                Spacer(Modifier.height(8.dp))
                monetization.BannerSlot(Modifier.fillMaxWidth())
            }
            // The one card back currently in flight from the deck to a pile, drawn above everything.
            FlyingDealCard(dealState)
            if (tutorial != null && tutorialAnchors != null && !dealState.dealing) {
                TutorialBubble(tutorial, view, botNames, tutorialAnchors, dealState.overlayOrigin)
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            inGame = true,
            monetization = monetization,
            onDismiss = { showSettings = false },
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Leave game?") },
            text = { Text(leaveConfirmText ?: "The current game will be lost.") },
            confirmButton = {
                TextButton(onClick = onExit, modifier = Modifier.testTag("confirmLeave")) {
                    Text("Leave")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Cancel") }
            },
        )
    }

    // At game end the final hand's score breakdown (HandResultDialog) shows first; the game-over
    // score sheet only appears once it has been dismissed.
    if (view.winner != null && finalResultAcked) {
        GameOverDialog(
            view = view,
            botNames = botNames,
            onBackToMenu = {
                // The game's only interstitial moment: once per finished game, on the way out (a
                // no-op that exits immediately in FOSS builds, when ads are removed, or before
                // consent).
                monetization.maybeShowInterstitial(onDismissed = onExit)
            },
        )
    }

    HandResultDialog(
        view = view,
        botNames = botNames,
        onDismiss = {
            if (tutorial != null) {
                // Deliberately never acknowledge the tutorial hand's result: the next deal's
                // animation and the ViewModel's bots both gate on it, so the finished board
                // stays put behind the epilogue pages instead of dealing a distracting hand 2.
                tutorialComplete = true
            } else {
                resultAckedHand = view.handNumber
                onResultDismiss(view.handNumber)
                if (view.winner != null) finalResultAcked = true
            }
        },
    )

    if (tutorial != null && tutorialComplete) {
        // A short epilogue (misère, no-trumps) pages before the completion dialog.
        var epiloguePage by rememberSaveable { mutableIntStateOf(0) }
        if (epiloguePage < tutorialEpilogue.size) {
            val page = tutorialEpilogue[epiloguePage]
            AlertDialog(
                onDismissRequest = {},
                title = { Text(page.title) },
                text = { Text(page.body, fontSize = 17.sp, lineHeight = 23.sp) },
                confirmButton = {
                    TextButton(
                        onClick = { epiloguePage++ },
                        modifier = Modifier.testTag("tutorialEpilogueNext"),
                    ) { Text("Next") }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Tutorial complete") },
                text = { Text(TUTORIAL_COMPLETION, fontSize = 17.sp, lineHeight = 23.sp) },
                confirmButton = {
                    TextButton(onClick = onExit, modifier = Modifier.testTag("tutorialCompleteContinue")) {
                        Text("Continue")
                    }
                },
                modifier = Modifier.testTag("tutorialComplete"),
            )
        }
    }
}
