// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.cardkit.ui.displayLabel
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlin.math.roundToInt

/**
 * The tutorial guidance as a speech bubble anchored to whatever needs interacting with next: it
 * floats just above the scripted bid button / card / hand with a tail pointing down at it, or sits
 * at the bottom of the felt with the tail pointing up while the bots act.
 */
@Composable
internal fun TutorialBubble(
    tutorial: TutorialScriptState,
    view: PlayerView,
    botNames: Map<Seat, String>,
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
    // A completed trick held on the felt (the tutorial forces the hold on): explain what happened.
    // Mirrors TrickArea's holdingTrick — after a completed trick, view.trickNumber IS that trick's
    // number (it advanced when the trick closed), so it keys tutorialTrickNotes directly.
    val lastTrick = view.lastTrick
    val trickHeld = view.phase == Phase.PLAY && view.currentTrick.isEmpty() &&
        lastTrick != null && !view.isMyTurn
    val text = when {
        step == null -> "That's the whole hand — see how it scored."
        isHumanDecision -> step.advice
        trickHeld -> tutorialTrickNotes[view.trickNumber]
            ?: "${seatLabel(view, botNames, lastTrick!!.winner)} won the trick — tap it to continue."
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
                        SuitText(text, fontSize = 17.sp, lineHeight = 23.sp)
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
