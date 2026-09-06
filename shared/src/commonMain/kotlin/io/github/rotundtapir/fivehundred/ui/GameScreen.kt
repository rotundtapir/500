// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
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
import io.github.rotundtapir.cardkit.ui.deal.DealAnimationState
import io.github.rotundtapir.cardkit.ui.CardAspectRatio
import io.github.rotundtapir.cardkit.ui.felt.feltTonalButtonColors
import io.github.rotundtapir.cardkit.ui.deal.DealingHandRow
import io.github.rotundtapir.cardkit.ui.deal.FlyingDealCard
import io.github.rotundtapir.cardkit.ui.deal.dealTimings
import io.github.rotundtapir.cardkit.ui.deal.runDealAnimation
import io.github.rotundtapir.cardkit.ui.tutorial.NarrationState
import io.github.rotundtapir.cardkit.ui.tutorial.NarrationToggle
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialAnchors
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialPage
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialPagesDialog
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialScriptState
import io.github.rotundtapir.cardkit.ui.tutorial.tutorialTarget
import io.github.rotundtapir.cardkit.ui.settings.AnimationSpeed
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.HAND_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.cardkit.net.Emote
import io.github.rotundtapir.cardkit.net.EmoteReceived
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
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
    tutorial: TutorialScriptState<TutorialStep>? = null,
    // Non-null while the tutorial narrates: drives the top-bar mute toggle and the spoken bubble.
    narration: NarrationState? = null,
    onResultDismiss: (Int) -> Unit = {},
    onDealAnimationFinish: (Int) -> Unit = {},
    onTrickAcknowledge: (Int, Int) -> Unit = { _, _ -> },
    soundHook: ((SoundEffect) -> Unit)? = null,
    // Online games override the leave-confirm body — leaving hands the seat to a bot, not a loss.
    leaveConfirmText: String? = null,
    // Non-null in an online game: adds the emote control to the top bar and shows incoming emotes.
    online: OnlineGameControls? = null,
    // The hidden cheats section (#51), or null. The caller passes null for an online game: the
    // cheats cannot work there, and the section should not even appear.
    cheats: CheatControls? = null,
    // Cheat-only: every seat's cards, from the local game state. The screen shows ONE of them at a
    // time (tap a seat's pile), because three face-up rows crowd the felt off a phone. Empty unless
    // the cheat is on, and necessarily empty online (the client never receives other hands).
    revealedHands: Map<Seat, List<Card>> = emptyMap(),
    // Cheat-only: the kitty's cards. Revealed with the hands — once all four hands are visible the
    // kitty is just the complement, so hiding it buys nothing.
    revealedKitty: List<Card> = emptyList(),
    // Bumped by the ViewModel per new match. A new game is hand 1 again, so without this the deal
    // bookkeeping below reads it as "the deal for hand 1 already ran" — see [gameGeneration] there.
    gameGeneration: Int = 0,
) {
    val animationSpeed = settings.animationSpeed
    // Captured by the deal LaunchedEffect below; rememberUpdatedState so a recomposition that
    // changes the callback identity doesn't leave the running effect holding a stale one.
    val currentOnDealAnimationFinish by rememberUpdatedState(onDealAnimationFinish)
    var sortHand by rememberSaveable { mutableStateOf(settings.sortByDefault) }
    var showSettings by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    // The most recent incoming emote, shown briefly as a speech bubble pointing at its sender.
    // seatAnchors records each seat's on-screen position for the bubble to point at.
    val seatAnchors = if (online != null) remember { TutorialAnchors() } else null
    var latestEmote by remember { mutableStateOf<EmoteReceived?>(null) }
    if (online != null) {
        LaunchedEffect(online) {
            online.incomingEmotes.collect { received ->
                latestEmote = received
                delay(EMOTE_TOAST_MILLIS)
                latestEmote = null
            }
        }
    }
    // Set once the tutorial's scripted hand has been scored and its result dialog dismissed.
    var tutorialComplete by rememberSaveable { mutableStateOf(false) }
    // Highest hand number whose result dialog has been dismissed — the shuffle/deal animation of
    // the NEXT hand waits for this, so nothing moves behind the dialog while the player reads it.
    // Saveable (keyed on the match): a rotation mid-hand used to reset it, which brought the
    // previous hand's already-dismissed dialog back as a modal over the live hand until the
    // player dismissed it a second time.
    var resultAckedHand by rememberSaveable(gameGeneration) { mutableIntStateOf(0) }
    // Whether the FINAL hand's result dialog has been dismissed. resultAckedHand can't tell: between
    // hands the dialog shows under the NEXT hand's number, so by game end it already reads current.
    // Keyed on winner so it resets if this composable survives into another game; saveable so a
    // rotation over the game-over sheet doesn't drop back to the final hand's breakdown.
    var finalResultAcked by rememberSaveable(view.winner) { mutableStateOf(false) }
    // Set once the game-over sheet's exit has been taken, so neither trigger can take it twice.
    var leaving by remember { mutableStateOf(false) }

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
    // Highest hand whose deal has finished showing (or was skipped as a mid-hand snapshot). While
    // view.handNumber is beyond this, the hand area renders NOTHING: a fresh hand held behind the
    // result dialog must not flash its cards and bid ladder before the shuffle has run.
    var dealtHand by rememberSaveable { mutableIntStateOf(0) }
    // Which match the deal bookkeeping below belongs to. Saveable alongside it so a recreation
    // doesn't look like a new game (which would replay a deal that already ran).
    var animatedGeneration by rememberSaveable { mutableIntStateOf(gameGeneration) }
    // Which seat's hand the reveal cheat is showing, if any. Cleared on a new match so a re-deal
    // does not leave a stale seat exposed, and tapping the shown seat again hides it.
    var revealedSeat by remember { mutableStateOf<Seat?>(null) }
    LaunchedEffect(gameGeneration) { revealedSeat = null }
    val revealAvailable = revealedHands.isNotEmpty()
    val onSeatTap: ((Seat) -> Unit)? = if (revealAvailable) {
        { seat -> revealedSeat = if (revealedSeat == seat) null else seat }
    } else {
        null
    }
    // Only the chosen seat's cards reach the row.
    val shownHand: Map<Seat, List<Card>> = revealedSeat
        ?.let { seat -> revealedHands[seat]?.let { mapOf(seat to it) } }
        ?: emptyMap()
    LaunchedEffect(view.handNumber, gameGeneration) {
        if (gameGeneration != animatedGeneration) {
            // A different match: forget the previous one's deal history so hand 1 is treated as a
            // genuine hand start — the deal runs and, crucially, its signal fires. Skipping that
            // signal is what left the bots waiting on an animation that never happened.
            animatedGeneration = gameGeneration
            lastAnimatedHand = 0
            dealtHand = 0
        }
        if (animationSpeed == AnimationSpeed.OFF) {
            // A hand dealt at OFF is already fully on screen — record it as dealt, or switching
            // animations back on mid-hand blanks the ActionArea for the rest of the hand (#40):
            // the blanking branch below sees handNumber > dealtHand and nothing ever advances it.
            dealtHand = maxOf(dealtHand, view.handNumber)
            lastAnimatedHand = view.handNumber
            return@LaunchedEffect
        }
        if (view.handNumber == lastAnimatedHand) {
            // Recreation mid-hand: the deal was already shown (or abandoned) — never leave the
            // hand area blanked waiting for an animation that won't replay.
            dealtHand = maxOf(dealtHand, view.handNumber)
            return@LaunchedEffect
        }
        lastAnimatedHand = view.handNumber
        // Only animate a genuine hand start (empty auction and no cards played yet). A view first
        // seen mid-hand — an online (re)connection snapshot — must not replay the deal; just release
        // the pacing signal so play proceeds.
        val handStart = view.phase == Phase.BIDDING && view.biddingHistory.isEmpty() && view.currentTrick.isEmpty()
        if (!handStart) {
            dealtHand = maxOf(dealtHand, view.handNumber)
            currentOnDealAnimationFinish(view.handNumber)
            return@LaunchedEffect
        }
        // Hold the shuffle until the previous hand's result dialog is dismissed.
        if (view.lastHandResult != null && view.winner == null) {
            snapshotFlow { resultAckedHand }.first { it >= view.handNumber }
        }
        runDealAnimation(
            dealState,
            fiveHundredDealSchedule(view.playerCount, view.dealer),
            dealTimings(animationSpeed),
            HAND_SIZE,
        )
        dealtHand = maxOf(dealtHand, view.handNumber)
        // Release the first bidder: the ViewModel waits on this signal, not a timer, so slow
        // devices can't start the auction mid-deal.
        currentOnDealAnimationFinish(view.handNumber)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { dealState.overlayOrigin = it.positionInRoot() },
        ) {
            // Size the chrome from the height we actually have, the way TrickArea already sizes the
            // felt's cards (#41): a portrait phone is unchanged, while a landscape phone or a short
            // window compacts the opponents' row and shrinks the fan instead of clipping it off the
            // bottom. Threshold is just above the tallest portrait chrome, so portrait never trips it.
            val shortScreen = maxHeight < SHORT_SCREEN_HEIGHT
            // A short screen that is also wider than it is tall (a phone in landscape) gets a
            // genuine landscape arrangement: the opponents move into a narrow column down the side,
            // which returns their whole height to the felt and the hand. Merely compacting them
            // vertically is not enough — a 390dp-tall viewport cannot fit the portrait stack at all.
            val sideBySide = shortScreen && maxWidth > maxHeight
            // The fan gets a fixed slice of the screen's height, floored so cards stay recognisable.
            // Side by side there is more height to spend on it, since nothing else is competing.
            val handFraction = if (sideBySide) HAND_HEIGHT_FRACTION_WIDE else HAND_HEIGHT_FRACTION
            val handCardWidth = (maxHeight * handFraction / CardAspectRatio)
                .coerceIn(MIN_HAND_CARD_WIDTH, HandCardWidth)
            // The felt and the player's own half, shared by both arrangements (one board, two
            // frames around it — duplicating it is how the two would drift apart).
            val board: @Composable ColumnScope.() -> Unit = {
                // Side by side the exposed hand lives in the panel instead: on the felt's side it
                // would squeeze the trick down to a sliver at landscape-phone heights.
                if (!sideBySide) ExposedDeclarerHand(view, botNames)
                RevealedHands(view, botNames, shownHand)
                TrickArea(
                    view = view,
                    botNames = botNames,
                    animationSpeed = animationSpeed,
                    dealState = dealState,
                    modifier = Modifier
                        .weight(1f)
                        // The felt may shrink, but never to nothing: a trick has to stay readable
                        // even when everything else has claimed its space (#41).
                        .heightIn(min = MIN_FELT_HEIGHT)
                        .tutorialTarget(tutorialAnchors, "trick"),
                    // Decided ONCE here — the same expression the ViewModel's pacing gates use
                    // (holdTricks || tutorial active). Two independent derivations of "is the felt
                    // holding" wedged euchre when they drifted; one boolean cannot.
                    holdTricks = settings.holdTricks || tutorial != null,
                    // The tutorial always holds completed tricks so the bubble can explain each
                    // outcome (still inert at OFF, like all pacing).
                    hideTapHint = tutorial != null,
                    onTrickAcknowledge = onTrickAcknowledge,
                    shortScreen = shortScreen,
                    revealedKitty = revealedKitty,
                )
                if (cheats?.redealOnFelt == true) {
                    // Under the felt rather than on it: the felt's own space is already tight in
                    // portrait, and a button overlaying it would sit on the trick cards.
                    OutlinedButton(
                        onClick = { cheats.onRedeal(null) },
                        colors = feltTonalButtonColors(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .height(30.dp)
                            .testTag("feltRedeal"),
                    ) {
                        Text("Re-deal ⟳", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (dealState.dealing) {
                    DealingHandRow(
                        cards = rememberDisplayHand(view, sortHand),
                        state = dealState,
                        humanSeat = view.seat,
                        timings = dealTimings(animationSpeed),
                        cardWidth = handCardWidth,
                    )
                } else if (animationSpeed != AnimationSpeed.OFF && view.handNumber > dealtHand) {
                    // A fresh hand whose shuffle is still held behind the result dialog: keep the
                    // new cards and bid ladder off screen until the deal actually runs.
                    Box(Modifier.fillMaxWidth())
                } else {
                    Box(Modifier.fillMaxWidth().tutorialTarget(seatAnchors, "seat:${view.seat.index}")) {
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
                            cardWidth = handCardWidth,
                        )
                    }
                }
            }
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
                    trailing = when {
                        online != null -> ({ OnlineEmoteButton(online) })
                        narration != null -> ({ NarrationToggle(narration, compact = true) })
                        else -> null
                    },
                )
                if (sideBySide) {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.width(SIDE_PANEL_WIDTH)) {
                            ContractLine(view, botNames)
                            Spacer(Modifier.height(8.dp))
                            OpponentsColumn(
                                view, botNames, dealState,
                                seatAnchors = seatAnchors,
                                onSeatTap = onSeatTap,
                            )
                            ExposedDeclarerHand(view, botNames, compact = true)
                            RevealedHands(view, botNames, shownHand, compact = true)
                        }
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) { board() }
                    }
                } else {
                    ContractLine(view, botNames)
                    Spacer(Modifier.height(12.dp))
                    OpponentsRow(
                        view, botNames, dealState, seatAnchors,
                        shortScreen = shortScreen,
                        onSeatTap = onSeatTap,
                    )
                    board()
                }
                Spacer(Modifier.height(8.dp))
                monetization.BannerSlot(Modifier.fillMaxWidth())
            }
            // Incoming emote, as a speech bubble pointing at its sender's seat.
            val emote = latestEmote
            if (emote != null && seatAnchors != null) {
                seatAnchors["seat:${emote.seat.index}"]?.let { rect ->
                    EmoteBubble(
                        target = rect,
                        overlayOrigin = dealState.overlayOrigin,
                        text = "${seatLabel(view, botNames, emote.seat)}: ${emoteLabel(emote.emote)}",
                        tailDown = emote.seat == view.seat,
                    )
                }
            }
            // The one card back currently in flight from the deck to a pile, drawn above everything.
            FlyingDealCard(dealState)
            if (tutorial != null && tutorialAnchors != null && !dealState.dealing) {
                TutorialBubble(tutorial, view, botNames, tutorialAnchors, dealState.overlayOrigin, narration)
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            inGame = true,
            monetization = monetization,
            onDismiss = { showSettings = false },
            cheats = cheats,
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
                // consent). Guarded: the sheet now has two triggers (button and system back), and
                // a second call while the ad is up would exit underneath it.
                if (!leaving) {
                    leaving = true
                    monetization.maybeShowInterstitial(onDismissed = onExit)
                }
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
        // The epilogue (misère, no-trumps) and completion pages, on the shared card-face pager.
        TutorialPagesDialog(
            pages = tutorialEpilogue + TutorialPage("Tutorial complete", TUTORIAL_COMPLETION),
            nextTag = "tutorialEpilogueNext",
            finishLabel = "Continue",
            finishTag = "tutorialCompleteContinue",
            onFinish = onExit,
            lastPageTag = "tutorialComplete",
            narration = narration,
            narrationUriFor = ::narrationUriFor,
        )
    }
}

/** The online-game hooks GameScreen needs: incoming emotes to show, and a way to send one. */
@Immutable
class OnlineGameControls(
    val incomingEmotes: SharedFlow<EmoteReceived>,
    val onSendEmote: (Emote) -> Unit,
)

/** The emote picker in the top bar: a small button opening a dropdown of the canned phrases. */
@Composable
private fun OnlineEmoteButton(controls: OnlineGameControls) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.testTag("emoteButton"),
        ) { Text("Emote") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EMOTE_OPTIONS.forEach { (emote, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        controls.onSendEmote(emote)
                        open = false
                    },
                    modifier = Modifier.testTag("emote:${emote.name}"),
                )
            }
        }
    }
}

private fun emoteLabel(emote: Emote): String =
    EMOTE_OPTIONS.firstOrNull { it.first == emote }?.second ?: emote.name

private const val EMOTE_TOAST_MILLIS = 2500L

private val EMOTE_OPTIONS = listOf(
    Emote.WELL_PLAYED to "Well played",
    Emote.NICE_HAND to "Nice hand",
    Emote.OOPS to "Oops",
    Emote.THINKING to "Hmm",
    Emote.HURRY_UP to "Hurry up",
    Emote.GOOD_GAME to "Good game",
)

/**
 * Below this height the game screen switches to its compact chrome (#41). Chosen just under the
 * shortest portrait phone viewport and well above a landscape phone's, so rotating is what trips
 * it, not a tall device.
 */
private val SHORT_SCREEN_HEIGHT = 600.dp

/** Share of the screen's height the human's fan may take, before the floor below applies. */
private const val HAND_HEIGHT_FRACTION = 0.21f

/** Side by side the opponents no longer compete for height, so the fan can have more of it. */
private const val HAND_HEIGHT_FRACTION_WIDE = 0.30f

/** The felt's floor — below this a trick stops being readable, so other things give way first. */
private val MIN_FELT_HEIGHT = 96.dp

/** Width of the landscape side panel: enough for a seat name plus its one-line status. */
private val SIDE_PANEL_WIDTH = 190.dp

/** The fan never shrinks past this: smaller and the pips stop being readable at arm's length. */
private val MIN_HAND_CARD_WIDTH = 48.dp
