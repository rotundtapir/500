// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.cardkit.ui.SuitText
import io.github.rotundtapir.cardkit.ui.felt.CardSurfaceWhite
import io.github.rotundtapir.cardkit.ui.felt.InkOnCardSurface
import io.github.rotundtapir.cardkit.ui.tutorial.BubbleLayout
import io.github.rotundtapir.cardkit.ui.tutorial.NarrateEffect
import io.github.rotundtapir.cardkit.ui.tutorial.NarrationState
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialAnchors
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialScriptState
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
    tutorial: TutorialScriptState<TutorialStep>,
    view: PlayerView,
    botNames: Map<Seat, String>,
    anchors: TutorialAnchors,
    overlayOrigin: Offset,
    narration: NarrationState? = null,
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
    // While the bots act there is deliberately NO bubble: an ever-present "watch the table" box
    // added noise (and narration) without teaching anything — the moving cards speak for themselves.
    val text = when {
        step == null -> TUTORIAL_HAND_DONE
        isHumanDecision -> step.advice
        trickHeld -> tutorialTrickNotes[view.trickNumber]
            ?: "${seatLabel(view, botNames, lastTrick.winner)} won the trick; tap it to continue."
        else -> return
    }
    val targetKey = when {
        !isHumanDecision -> "trick"
        step is TutorialStep.BidStep -> "action"
        step is TutorialStep.PlayStep -> "card:${step.card.label}"
        else -> "action" // discard: sit above the whole panel (header anchor), tail at its centre
    }
    val tailDown = targetKey != "trick"
    val target = anchors[targetKey] ?: anchors["hand"] ?: anchors["trick"] ?: return
    val showTrumpOrder = isHumanDecision && step?.showTrumpOrder == true
    NarrateEffect(narration, text, uriFor = ::narrationUriFor)

    BubbleLayout(
        target = target,
        overlayOrigin = overlayOrigin,
        tailDown = tailDown,
        maxWidth = 520.dp,
        yPlacement = { local, height, gap ->
            if (tailDown) {
                (local.top - height - gap).roundToInt()
            } else {
                (local.bottom - height - gap).roundToInt()
            }
        },
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = CardSurfaceWhite,
            contentColor = InkOnCardSurface,
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
