// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.ui.CardHand
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.cardkit.ui.displayLabel
import kotlin.math.roundToInt
import io.github.rotundtapir.fivehundred.AnimationSpeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.Trump
import io.github.rotundtapir.fivehundred.engine.label
import io.github.rotundtapir.fivehundred.engine.teamOf

private fun seatLabel(view: PlayerView, botNames: Map<Seat, String>, seat: Seat): String =
    if (seat == view.seat) "You" else botNames[seat] ?: "Seat ${seat.index}"

/** The seats on [team], in seat order. */
private fun teamSeats(view: PlayerView, team: Int): List<Seat> =
    (0 until view.playerCount).map(::Seat).filter { teamOf(it, view.teamCount) == team }

/**
 * A short name for another team, built from its members ("Gus & Ivy") — used where "Them" is
 * ambiguous, i.e. whenever there is more than one opposing team.
 */
private fun teamLabel(view: PlayerView, botNames: Map<Seat, String>, team: Int): String =
    teamSeats(view, team).joinToString(" & ") { seatLabel(view, botNames, it) }

/** Hand order for display: trumps (both bowers + Joker) first, then alternating-colour suits, strongest first. */
private fun sortedForDisplay(hand: List<Card>, trump: Trump?): List<Card> {
    val eval = TrickEvaluator(trump ?: Trump.NO_TRUMP)
    val suitOrder = listOf(Suit.SPADES, Suit.HEARTS, Suit.CLUBS, Suit.DIAMONDS)
    return hand.sortedWith(
        compareBy(
            { card -> if (eval.isTrump(card)) 0 else 1 + suitOrder.indexOf(eval.effectiveSuit(card)) },
            { card -> -eval.strength(card, eval.effectiveSuit(card)) },
        ),
    )
}

private fun signed(delta: Int): String = if (delta < 0) "−${-delta}" else "+$delta"

@Composable
fun GameScreen(
    view: PlayerView,
    botNames: Map<Seat, String>,
    animationSpeed: AnimationSpeed,
    defaultSortHand: Boolean,
    monetization: Monetization,
    activity: Activity,
    onBid: (Bid) -> Unit,
    onDiscard: (List<Card>) -> Unit,
    onPlay: (Card) -> Unit,
    onExit: () -> Unit,
    tutorial: TutorialScriptState? = null,
    onResultDismissed: (Int) -> Unit = {},
    onDealAnimationFinished: (Int) -> Unit = {},
    holdTricks: Boolean = false,
    onToggleHoldTricks: () -> Unit = {},
    onTrickAcknowledged: (Int, Int) -> Unit = { _, _ -> },
) {
    var sortHand by rememberSaveable { mutableStateOf(defaultSortHand) }
    // Set once the tutorial's scripted hand has been scored and its result dialog dismissed.
    var tutorialComplete by rememberSaveable { mutableStateOf(false) }
    // Highest hand number whose result dialog has been dismissed — the shuffle/deal animation of
    // the NEXT hand waits for this, so nothing moves behind the dialog while the player reads it.
    var resultAckedHand by remember { mutableStateOf(0) }

    // Dealing animation: on each new hand (unless animations are OFF) fly card backs one at a time
    // from a centre deck to each seat's pile / the kitty in 500's 3-4-3 packet order, then flip the
    // human's face-down row face up. While it runs the ActionArea is hidden so the player can't bid
    // mid-deal. Tests run at OFF, where this is skipped entirely (dealState.stage stays DONE).
    // lastAnimatedHand is saveable so recreation doesn't replay the deal.
    val dealState = remember { DealAnimationState() }
    // Screen rects of the tutorial's interaction targets (bid button, cards, felt), for the bubble.
    val tutorialTargets = if (tutorial != null) remember { mutableStateMapOf<String, Rect>() } else null
    var lastAnimatedHand by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(view.handNumber) {
        if (animationSpeed == AnimationSpeed.OFF) return@LaunchedEffect
        if (view.handNumber == lastAnimatedHand) return@LaunchedEffect
        lastAnimatedHand = view.handNumber
        // Hold the shuffle until the previous hand's result dialog is dismissed.
        if (view.lastHandResult != null && view.winner == null) {
            snapshotFlow { resultAckedHand }.first { it >= view.handNumber }
        }
        runDealAnimation(dealState, view.playerCount, view.dealer, animationSpeed)
        // Release the first bidder: the ViewModel waits on this signal, not a timer, so slow
        // devices can't start the auction mid-deal.
        onDealAnimationFinished(view.handNumber)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
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
                ScoreBar(view, botNames, onExit)
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
                        .tutorialTarget(tutorialTargets, "trick"),
                    holdTricks = holdTricks,
                    onToggleHoldTricks = onToggleHoldTricks,
                    onTrickAcknowledged = onTrickAcknowledged,
                )
                if (dealState.dealing) {
                    DealingHandRow(
                        cards = if (sortHand) sortedForDisplay(view.hand, view.trump) else view.hand,
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
                        targets = tutorialTargets,
                    )
                }
                Spacer(Modifier.height(8.dp))
                monetization.BannerSlot(Modifier.fillMaxWidth())
            }
            // The one card back currently in flight from the deck to a pile, drawn above everything.
            FlyingDealCard(dealState)
            if (tutorial != null && tutorialTargets != null && !dealState.dealing) {
                TutorialBubble(tutorial, view, tutorialTargets, dealState.overlayOrigin)
            }
        }
    }

    view.winner?.let { winningTeam ->
        val youWon = winningTeam == view.myTeam
        val finalScore = if (view.teamCount == 2) {
            "Final score — you ${view.scores[view.myTeam]}, opponents ${view.scores[1 - view.myTeam]}"
        } else {
            // Three teams: list every team's score, ours first, the others named by their members.
            val others = (0 until view.teamCount).filter { it != view.myTeam }
            val parts = listOf("You ${view.scores[view.myTeam] ?: 0}") +
                others.map { team -> "${teamLabel(view, botNames, team)} ${view.scores[team] ?: 0}" }
            "Final score — ${parts.joinToString(" · ")}"
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (youWon) "You win!" else "You lose") },
            text = { Text(finalScore) },
            confirmButton = { TextButton(onClick = onExit) { Text("Back to menu") } },
        )
    }

    HandResultDialog(
        view = view,
        botNames = botNames,
        onDismissed = {
            resultAckedHand = view.handNumber
            onResultDismissed(view.handNumber)
            if (tutorial != null) tutorialComplete = true
        },
    )

    if (tutorial != null && tutorialComplete) {
        // A short epilogue (misère, no-trumps) pages before the completion dialog.
        var epiloguePage by rememberSaveable { mutableStateOf(0) }
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

/** Records this composable's window bounds under [key] for the tutorial bubble to anchor to. */
private fun Modifier.tutorialTarget(map: MutableMap<String, Rect>?, key: String): Modifier =
    if (map == null) this else onGloballyPositioned { map[key] = it.boundsInRoot() }

/** Clickable only while [enabled] — written as a factory to avoid conditional `.then` chains. */
private fun Modifier.tappableWhen(enabled: Boolean, onTap: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onTap) else this

/**
 * The tutorial guidance as a speech bubble anchored to whatever needs interacting with next: it
 * floats just above the scripted bid button / card / hand with a tail pointing down at it, or sits
 * at the bottom of the felt with the tail pointing up while the bots act.
 */
@Composable
private fun TutorialBubble(
    tutorial: TutorialScriptState,
    view: PlayerView,
    targets: Map<String, Rect>,
    overlayOrigin: Offset,
) {
    val step = tutorial.step
    val isHumanDecision = when (step) {
        is TutorialStep.BidStep -> view.phase == Phase.BIDDING && view.isMyTurn
        is TutorialStep.DiscardStep -> view.phase == Phase.KITTY && view.mustDiscard > 0
        is TutorialStep.PlayStep -> view.phase == Phase.PLAY && view.isMyTurn
        null -> false
    }
    val text = when {
        step == null -> "That's the whole hand — see how it scored."
        isHumanDecision -> step.advice
        else -> "Watch the table — the other players are acting…"
    }
    val targetKey = when {
        !isHumanDecision -> "trick"
        step is TutorialStep.BidStep -> "action"
        step is TutorialStep.PlayStep -> "card:${step.card.displayLabel}"
        else -> "action" // discard: sit above the whole panel (header anchor), tail at its centre
    }
    val tailDown = targetKey != "trick"
    val target = targets[targetKey] ?: targets["hand"] ?: targets["trick"] ?: return
    val showTrumpOrder = isHumanDecision && step?.showTrumpOrder == true

    val density = LocalDensity.current
    var bubbleLeft by remember { mutableStateOf(0) }
    val local = target.translate(-overlayOrigin)
    val tailWidth = with(density) { 26.dp.toPx() }

    Layout(
        content = {
            Column {
                if (!tailDown) {
                    BubbleTail(
                        pointUp = true,
                        offsetX = { (local.center.x - bubbleLeft - tailWidth / 2).roundToInt() },
                    )
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFFAFAFA),
                    contentColor = MaterialTheme.colorScheme.primary,
                    shadowElevation = 8.dp,
                    modifier = Modifier.testTag("tutorialAdvice"),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Tutorial", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(text, fontSize = 17.sp, lineHeight = 23.sp)
                        if (showTrumpOrder) {
                            Spacer(Modifier.height(8.dp))
                            TrumpOrderRow()
                        }
                    }
                }
                if (tailDown) {
                    BubbleTail(
                        pointUp = false,
                        offsetX = { (local.center.x - bubbleLeft - tailWidth / 2).roundToInt() },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val margin = with(density) { 12.dp.roundToPx() }
        val maxWidth = minOf(constraints.maxWidth - margin * 2, with(density) { 520.dp.roundToPx() })
        val placeable = measurables[0].measure(
            Constraints(minWidth = 0, maxWidth = maxWidth, minHeight = 0, maxHeight = constraints.maxHeight),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (local.center.x - placeable.width / 2f).roundToInt()
                .coerceIn(margin, (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin))
            val gap = with(density) { 2.dp.roundToPx() }
            val y = if (tailDown) {
                (local.top - placeable.height - gap).roundToInt().coerceAtLeast(margin)
            } else {
                (local.bottom - placeable.height - gap).roundToInt().coerceAtLeast(margin)
            }
            bubbleLeft = x
            placeable.place(x, y)
        }
    }
}

/** The bubble's little triangular tail, slid horizontally to point at the anchor. */
@Composable
private fun BubbleTail(pointUp: Boolean, offsetX: () -> Int) {
    val tailColor = Color(0xFFFAFAFA)
    Canvas(
        modifier = Modifier
            .offset { IntOffset(offsetX().coerceAtLeast(0), 0) }
            .size(26.dp, 12.dp),
    ) {
        val path = Path().apply {
            if (pointUp) {
                moveTo(0f, size.height)
                lineTo(size.width, size.height)
                lineTo(size.width / 2f, 0f)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
            }
            close()
        }
        drawPath(path, tailColor)
    }
}

/** The trump pecking order for the tutorial's bower moments: Joker, right bower, left bower, Ace. */
@Composable
private fun TrumpOrderRow() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayingCard(Joker, width = 40.dp)
            Text(">", fontWeight = FontWeight.Bold)
            PlayingCard(SuitedCard(Rank.JACK, Suit.SPADES), width = 40.dp)
            Text(">", fontWeight = FontWeight.Bold)
            PlayingCard(SuitedCard(Rank.JACK, Suit.CLUBS), width = 40.dp)
            Text(">", fontWeight = FontWeight.Bold)
            PlayingCard(SuitedCard(Rank.ACE, Suit.SPADES), width = 40.dp)
        }
        Spacer(Modifier.height(2.dp))
        Text("Trump order with spades as trumps", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HandResultDialog(
    view: PlayerView,
    botNames: Map<Seat, String>,
    onDismissed: () -> Unit = {},
) {
    val result = view.lastHandResult ?: return
    if (view.winner != null) return // the winner dialog handles game end
    var dismissed by remember(view.lastHandResult) { mutableStateOf(false) }
    if (dismissed) return
    val dismiss = {
        dismissed = true
        onDismissed()
    }

    val contract = result.contract
    val declarerTeam = teamOf(contract.declarer, view.teamCount)
    val myTeam = view.myTeam
    val myDelta = result.teamDeltas[myTeam] ?: 0
    // Our team first, then every other team in index order (just "Them" with two teams).
    val teamsInOrder = listOf(myTeam) + (0 until view.teamCount).filter { it != myTeam }

    val bidLine = buildString {
        append("${seatLabel(view, botNames, contract.declarer)} bid ${contract.bid.label}")
        if (!contract.isMisere) append(" — needed ${contract.level} tricks")
    }
    val tricksLine = if (contract.isMisere) {
        "Declarer took ${result.declarerTricks} tricks (needed none)"
    } else {
        "Declarer's side took ${result.declarerTricks} of 10 tricks"
    }

    fun rowLabel(team: Int): String = when {
        team == myTeam -> "Us"
        view.teamCount == 2 -> "Them"
        else -> teamLabel(view, botNames, team)
    }

    fun explanation(team: Int): String {
        // A defending team's delta is exactly 10 × the tricks its own members took.
        val teamTricks = (result.teamDeltas[team] ?: 0) / 10
        return when {
            team == declarerTeam -> if (result.made) "contract made" else "contract failed"
            contract.isMisere -> "defenders don't score"
            else -> "$teamTricks ${if (teamTricks == 1) "trick" else "tricks"} × 10"
        }
    }

    // Header tint follows the human's fortunes, not the declarer's: green-ish when our side gained
    // points this hand, red-ish otherwise.
    val gained = myDelta > 0
    val headerColor = if (gained) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val onHeaderColor = if (gained) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError

    Dialog(onDismissRequest = dismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerColor)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (result.made) "Contract made!" else "Contract failed",
                        style = MaterialTheme.typography.titleLarge,
                        color = onHeaderColor,
                    )
                }
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(bidLine, style = MaterialTheme.typography.bodyMedium)
                    Text(tricksLine, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    teamsInOrder.forEach { team ->
                        ScoreDeltaRow(rowLabel(team), result.teamDeltas[team] ?: 0, explanation(team))
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = dismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("handResultContinue"),
                    ) { Text("Continue") }
                }
            }
        }
    }
}

/** One side's line in the hand-result score table: label left, prominent delta + explanation right. */
@Composable
private fun ScoreDeltaRow(label: String, delta: Int, explanation: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Column(horizontalAlignment = Alignment.End) {
            Text(signed(delta), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (explanation.isNotEmpty()) {
                Text(explanation, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ScoreBar(view: PlayerView, botNames: Map<Seat, String>, onExit: () -> Unit) {
    val myTeam = view.myTeam
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (view.teamCount == 2) {
            Text("Us: ${view.scores[myTeam] ?: 0}", fontWeight = FontWeight.Bold)
            Text("Them: ${view.scores[1 - myTeam] ?: 0}", fontWeight = FontWeight.Bold)
        } else {
            // Three teams: name each opposing team by its members. The line is long, so use small
            // typography and let it wrap onto a second line rather than truncate.
            val others = (0 until view.teamCount).filter { it != myTeam }
            val entries = listOf("Us: ${view.scores[myTeam] ?: 0}") +
                others.map { team -> "${teamLabel(view, botNames, team)}: ${view.scores[team] ?: 0}" }
            Text(
                entries.joinToString("   "),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            onClick = onExit,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
        ) { Text("Menu") }
    }
}

@Composable
private fun ContractLine(view: PlayerView, botNames: Map<Seat, String>) {
    val text = when {
        view.contract != null -> "Contract: ${seatLabel(view, botNames, view.contract!!.declarer)} · ${view.contract!!.bid.label}"
        view.phase == Phase.BIDDING -> "Bidding" + (view.highBid?.let { " · high: ${it.label}" } ?: "")
        else -> ""
    }
    val last = view.lastHandResult?.let {
        "  (last: ${it.contract.bid.label} ${if (it.made) "made" else "failed"})"
    } ?: ""
    Text(text + last)
}

@Composable
private fun OpponentsRow(view: PlayerView, botNames: Map<Seat, String>, dealState: DealAnimationState) {
    // With 5 opponents (6-player game) the row gets tight: shrink each column and allow the row to
    // scroll horizontally as a safety valve on narrow screens.
    val compact = view.playerCount == 6
    val rowModifier = if (compact) {
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier,
        horizontalArrangement = if (compact) Arrangement.spacedBy(12.dp) else Arrangement.SpaceEvenly,
    ) {
        for (i in 0 until view.playerCount) {
            val seat = Seat(i)
            if (seat == view.seat) continue
            OpponentStatus(view, botNames, seat, compact, dealState)
        }
    }
}

@Composable
private fun OpponentStatus(
    view: PlayerView,
    botNames: Map<Seat, String>,
    seat: Seat,
    compact: Boolean,
    dealState: DealAnimationState,
) {
    val textStyle = if (compact) MaterialTheme.typography.bodySmall else LocalTextStyle.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val active = seat in view.activeSeats
        Text(
            seatLabel(view, botNames, seat),
            style = textStyle,
            fontWeight = if (view.toAct == seat) FontWeight.Bold else FontWeight.Normal,
        )
        if (teamOf(seat, view.teamCount) == view.myTeam) {
            Text("(partner)", style = MaterialTheme.typography.labelSmall)
        }
        if (active) {
            OpponentPile(
                seat = seat,
                state = dealState,
                width = if (compact) 32.dp else 44.dp,
                handSize = view.handSizes[seat] ?: 0,
            )
        } else {
            Text("(sitting out)", style = textStyle)
        }
        val cardCount = if (dealState.dealing) dealState.dealtTo(seat) else view.handSizes[seat] ?: 0
        Text("cards: $cardCount", style = textStyle)
        Text("tricks: ${view.tricksWon[seat] ?: 0}", style = textStyle)
        // Auction actions stay visible through the kitty exchange: knowing who bid what (and in
        // which suit) informs which side suits the declarer should shorten. During the exchange
        // the LAST REAL BID matters, not the pass that ended the auction — someone who bid 6♥ and
        // then passed still told you where their strength is.
        if (view.phase == Phase.BIDDING) {
            val lastAction = view.biddingHistory.lastOrNull { it.first == seat }?.second
            Text(
                when {
                    lastAction == null -> "—"
                    lastAction == Bid.Pass -> "passed"
                    else -> "bid ${lastAction.label}"
                },
                style = textStyle,
            )
        } else if (view.phase == Phase.KITTY) {
            val lastRealBid = view.biddingHistory
                .lastOrNull { it.first == seat && it.second != Bid.Pass }?.second
            Text(
                if (lastRealBid != null) "bid ${lastRealBid.label}" else "no bid",
                style = textStyle,
            )
        }
    }
}

/**
 * During an open-misère PLAY phase every defender sees the declarer's exposed hand; render it as a
 * labelled row of small face-up cards above the trick area. Null (and absent) for the declarer.
 */
@Composable
private fun ExposedDeclarerHand(view: PlayerView, botNames: Map<Seat, String>) {
    val exposed = view.exposedDeclarerHand ?: return
    val declarer = view.contract?.declarer ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${seatLabel(view, botNames, declarer)}'s hand (open misère)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            exposed.forEach { card -> PlayingCard(card, width = 40.dp) }
        }
    }
}

@Composable
private fun TrickArea(
    view: PlayerView,
    botNames: Map<Seat, String>,
    animationSpeed: AnimationSpeed,
    dealState: DealAnimationState,
    modifier: Modifier = Modifier,
    holdTricks: Boolean = false,
    onToggleHoldTricks: () -> Unit = {},
    onTrickAcknowledged: (Int, Int) -> Unit = { _, _ -> },
) {
    // With "Hold tricks" on, a completed trick stays on the felt until the player taps it away —
    // time to memorise the cards for counting. Toggleable mid-hand for the tricks that matter.
    val holdingTrick = holdTricks &&
        animationSpeed != AnimationSpeed.OFF &&
        !dealState.dealing &&
        view.phase == Phase.PLAY &&
        view.currentTrick.isEmpty() &&
        view.lastTrick != null &&
        !view.isMyTurn
    // The "felt" — a slightly darker rounded table centre where the current trick lands.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(Color(0x22000000), RoundedCornerShape(16.dp))
            .tappableWhen(holdingTrick) { onTrickAcknowledged(view.handNumber, view.trickNumber) },
        contentAlignment = Alignment.Center,
    ) {
        // Visible for the whole hand so the player can hold exactly the tricks they care about
        // (hidden at OFF, where no pacing — including the hold — applies).
        if (view.phase == Phase.PLAY && !dealState.dealing && animationSpeed != AnimationSpeed.OFF) {
            OutlinedButton(
                onClick = onToggleHoldTricks,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (holdTricks) {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f)
                    } else {
                        Color.Transparent
                    },
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .height(30.dp)
                    .testTag("holdToggle"),
            ) {
                Text(
                    if (holdTricks) "Hold tricks: on" else "Hold tricks: off",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (dealState.dealing) {
            // Deck + growing kitty pile while cards fly out.
            DealFelt(dealState)
        } else if (view.phase == Phase.BIDDING) {
            // The kitty sits face down on the felt for the whole auction (all speeds, incl. OFF);
            // it leaves the table once the contract is decided.
            KittyPile(count = KITTY_SIZE)
        } else {
            AnimatedContent(
                targetState = view,
                contentKey = { it.currentTrick.size to it.trickNumber },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "trickArea",
            ) { v ->
                val lastTrick = v.lastTrick
                when {
                    v.currentTrick.isNotEmpty() -> TrickPlaysRow(v, botNames, v.currentTrick)
                    lastTrick != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TrickPlaysRow(v, botNames, lastTrick.plays)
                        Spacer(Modifier.height(4.dp))
                        Text("${seatLabel(v, botNames, lastTrick.winner)} won the trick")
                        if (holdingTrick) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "tap to continue",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            )
                        }
                    }
                    else -> Text(
                        when {
                            v.phase == Phase.PLAY && v.isMyTurn -> "You lead"
                            v.phase == Phase.PLAY -> "Waiting for the first card…"
                            else -> ""
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrickPlaysRow(view: PlayerView, botNames: Map<Seat, String>, plays: List<TrickPlay>) {
    // Six-player tricks don't fit in one row on a phone — split into two rows (3+3, or 3+2)
    // so the cards stay full size.
    if (plays.size <= 4) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            plays.forEach { play -> TrickPlayCell(view, botNames, play) }
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            plays.chunked((plays.size + 1) / 2).forEach { rowPlays ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowPlays.forEach { play -> TrickPlayCell(view, botNames, play) }
                }
            }
        }
    }
}

@Composable
private fun TrickPlayCell(view: PlayerView, botNames: Map<Seat, String>, play: TrickPlay) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PlayingCard(play.card, width = 56.dp)
        Spacer(Modifier.height(4.dp))
        Text(seatLabel(view, botNames, play.seat), maxLines = 1)
    }
}

@Composable
private fun ActionArea(
    view: PlayerView,
    botNames: Map<Seat, String>,
    sortHand: Boolean,
    onToggleSort: () -> Unit,
    onBid: (Bid) -> Unit,
    onDiscard: (List<Card>) -> Unit,
    onPlay: (Card) -> Unit,
    tutorial: TutorialScriptState? = null,
    targets: MutableMap<String, Rect>? = null,
) {
    // In the tutorial only the scripted action is enabled, and taking it advances the script.
    val step = tutorial?.step
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // "You" mirror of the opponents' status line — during the auction it shows your latest bid instead.
        val myLastBid =
            if (view.phase == Phase.BIDDING) view.biddingHistory.lastOrNull { it.first == view.seat }?.second else null
        Text(
            when {
                myLastBid == Bid.Pass -> "You — passed"
                myLastBid != null -> "You — bid: ${myLastBid.label}"
                else -> "You — tricks: ${view.tricksWon[view.seat] ?: 0}"
            },
            fontWeight = if (view.isMyTurn) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(4.dp))
        when {
            view.phase == Phase.BIDDING && view.isMyTurn -> {
                BiddingPanel(
                    view = view,
                    onBid = { bid ->
                        tutorial?.onAdvance()
                        onBid(bid)
                    },
                    bidEnabled = if (tutorial == null) {
                        { true }
                    } else {
                        { it == (step as? TutorialStep.BidStep)?.bid }
                    },
                    anchorBid = (step as? TutorialStep.BidStep)?.bid,
                    targets = targets,
                )
                HumanHand(
                    view, sortHand, onToggleSort,
                    playable = { false }, dimUnplayable = false, onClick = {}, targets = targets,
                )
            }
            view.phase == Phase.KITTY && view.mustDiscard > 0 -> {
                DiscardPanel(
                    view = view,
                    sortHand = sortHand,
                    onToggleSort = onToggleSort,
                    onDiscard = { cards ->
                        tutorial?.onAdvance()
                        onDiscard(cards)
                    },
                    requiredDiscards = if (tutorial == null) {
                        null
                    } else {
                        (step as? TutorialStep.DiscardStep)?.cards?.toSet() ?: emptySet()
                    },
                    targets = targets,
                )
            }
            view.phase == Phase.PLAY && view.isMyTurn -> {
                Text("Your turn — tap a card to play")
                Spacer(Modifier.height(4.dp))
                val scriptedCard = (step as? TutorialStep.PlayStep)?.card
                HumanHand(
                    view = view,
                    sortHand = sortHand,
                    onToggleSort = onToggleSort,
                    playable = { card ->
                        card in view.legalPlays && (tutorial == null || card == scriptedCard)
                    },
                    onClick = { card ->
                        tutorial?.onAdvance()
                        onPlay(card)
                    },
                    targets = targets,
                )
            }
            else -> {
                Text(view.toAct?.let { "Waiting for ${seatLabel(view, botNames, it)}…" } ?: "")
                Spacer(Modifier.height(4.dp))
                HumanHand(
                    view, sortHand, onToggleSort,
                    playable = { false }, dimUnplayable = false, onClick = {}, targets = targets,
                )
            }
        }
    }
}

@Composable
private fun BiddingPanel(
    view: PlayerView,
    onBid: (Bid) -> Unit,
    bidEnabled: (Bid) -> Boolean = { true },
    anchorBid: Bid? = null,
    targets: MutableMap<String, Rect>? = null,
) {
    // Guard against double taps: one bid per PlayerView.
    var acted by remember(view) { mutableStateOf(false) }
    Text("Your bid:", fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Pass first, then the ranked contracts.
        view.legalBids.sortedBy { it != Bid.Pass }.forEach { bid ->
            OutlinedButton(
                onClick = {
                    acted = true
                    onBid(bid)
                },
                enabled = !acted && bidEnabled(bid),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)),
                modifier = Modifier
                    .testTag("bid:${bid.label}")
                    .tutorialTarget(if (bid == anchorBid) targets else null, "action"),
            ) { Text(bid.label) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DiscardPanel(
    view: PlayerView,
    sortHand: Boolean,
    onToggleSort: () -> Unit,
    onDiscard: (List<Card>) -> Unit,
    // Tutorial constraint: when non-null, only these cards are selectable and the discard arms
    // only once exactly they are selected.
    requiredDiscards: Set<Card>? = null,
    targets: MutableMap<String, Rect>? = null,
) {
    var selected by remember(view.hand) { mutableStateOf(emptySet<Card>()) }
    // Guard against double taps: one discard per PlayerView.
    var acted by remember(view) { mutableStateOf(false) }
    Text(
        "Discard $KITTY_SIZE cards to the kitty (${selected.size}/$KITTY_SIZE selected)",
        fontWeight = FontWeight.Bold,
        modifier = Modifier.tutorialTarget(targets, "action"),
    )
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = {
            acted = true
            onDiscard(selected.toList())
        },
        enabled = selected.size == KITTY_SIZE && !acted &&
            (requiredDiscards == null || selected == requiredDiscards),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFAFAFA),
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.testTag("discardButton"),
    ) { Text("Discard") }
    Spacer(Modifier.height(4.dp))
    HumanHand(
        view = view,
        sortHand = sortHand,
        onToggleSort = onToggleSort,
        playable = { requiredDiscards == null || it in requiredDiscards },
        selected = selected,
        onClick = { card ->
            selected = if (card in selected) selected - card
            else if (selected.size < KITTY_SIZE) selected + card else selected
        },
    )
}

@Composable
private fun HumanHand(
    view: PlayerView,
    sortHand: Boolean,
    onToggleSort: () -> Unit,
    playable: (Card) -> Boolean,
    onClick: (Card) -> Unit,
    dimUnplayable: Boolean = true,
    selected: Set<Card> = emptySet(),
    targets: MutableMap<String, Rect>? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onToggleSort,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.onBackground,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
            modifier = Modifier.height(30.dp).testTag("sortToggle"),
        ) {
            Text(
                if (sortHand) "Sorted ⇄" else "Deal order ⇄",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        val hand = if (sortHand) sortedForDisplay(view.hand, view.trump) else view.hand
        // The fan is wider than the screen at this card size — scroll it horizontally.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .tutorialTarget(targets, "hand"),
            contentAlignment = Alignment.Center,
        ) {
            CardHand(
                cards = hand,
                cardWidth = 84.dp,
                overlap = 0.45f,
                playable = playable,
                dimUnplayable = dimUnplayable,
                selected = selected,
                onCardClick = onClick,
                cardModifier = { card ->
                    Modifier.tutorialTarget(targets, "card:${card.displayLabel}")
                },
            )
        }
    }
}
