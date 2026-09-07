// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.ui.CardHand
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialAnchors
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialScriptState
import io.github.rotundtapir.cardkit.ui.tutorial.tutorialTarget
import io.github.rotundtapir.cardkit.ui.SuitText
import io.github.rotundtapir.cardkit.ui.felt.CardSurfaceWhite
import io.github.rotundtapir.cardkit.ui.felt.DealerButton
import io.github.rotundtapir.cardkit.ui.felt.DealerButtonSize
import io.github.rotundtapir.cardkit.ui.felt.OnBackgroundOutlinedButton
import io.github.rotundtapir.cardkit.ui.felt.feltTonalButtonColors
import io.github.rotundtapir.cardkit.ui.felt.InkOnCardSurface
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
internal fun ActionArea(
    view: PlayerView,
    botNames: Map<Seat, String>,
    sortHand: Boolean,
    onToggleSort: () -> Unit,
    onBid: (Bid) -> Unit,
    onDiscard: (List<Card>) -> Unit,
    onPlay: (Card) -> Unit,
    tutorial: TutorialScriptState<TutorialStep>? = null,
    targets: TutorialAnchors? = null,
    // Tutorial: peek-scroll the hand on the kitty-exchange step so the off-screen cards are seen.
    peekDiscardHand: Boolean = false,
    // Card width for the human's fan, scaled to the space the screen actually has (#41): the caller
    // derives it from available height, so a short screen shrinks the fan instead of clipping it.
    cardWidth: Dp = HandCardWidth,
) {
    // In the tutorial only the scripted action is enabled, and taking it advances the script.
    val step = tutorial?.step
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // "You" mirror of the opponents' status line — during the auction it shows your latest bid instead.
        val myLastBid =
            if (view.phase == Phase.BIDDING) view.biddingHistory.lastOrNull { it.first == view.seat }?.second else null
        SuitText(
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
                    cardWidth = cardWidth,
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
                    peekOnAppear = peekDiscardHand,
                    cardWidth = cardWidth,
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
                    cardWidth = cardWidth,
                )
            }
            else -> {
                Text(view.toAct?.let { "Waiting for ${seatLabel(view, botNames, it)}…" } ?: "")
                Spacer(Modifier.height(4.dp))
                HumanHand(
                    view, sortHand, onToggleSort,
                    playable = { false }, dimUnplayable = false, onClick = {}, targets = targets,
                    cardWidth = cardWidth,
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
    targets: TutorialAnchors? = null,
) {
    // Guard against double taps: one bid per PlayerView.
    var acted by remember(view) { mutableStateOf(false) }
    // The tutorial's scripted bid can start outside the scrollable ladder's viewport (a phone fits
    // roughly five buttons, and the script opens at 7♠). Scroll it into view: the player must be
    // able to SEE the button the advice names, and a fully-clipped target reports an empty rect,
    // which would strand the guidance bubble at the screen origin.
    val anchorInView = remember { BringIntoViewRequester() }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your bid:", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Pass first, then the ranked contracts.
            view.legalBids.sortedBy { it != Bid.Pass }.forEach { bid ->
                // Enabled bids are solid card-white pills (cardkit's emphasized felt button), so
                // the suit symbols keep their true card colors and the solid-vs-ghost split makes
                // the one enabled button unmistakable in the tutorial.
                OnBackgroundOutlinedButton(
                    onClick = {
                        acted = true
                        onBid(bid)
                    },
                    enabled = !acted && bidEnabled(bid),
                    emphasized = true,
                    modifier = Modifier
                        .testTag("bid:${bid.label}")
                        .bringIntoViewVia(anchorInView.takeIf { bid == anchorBid })
                        .tutorialTarget(if (bid == anchorBid) targets else null, "action"),
                ) { SuitText(bid.label) }
            }
        }
        if (anchorBid != null) {
            LaunchedEffect(anchorBid) { anchorInView.bringIntoView() }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Attaches [requester] when non-null — the factory-style conditional-modifier shape (see CLAUDE.md). */
private fun Modifier.bringIntoViewVia(requester: BringIntoViewRequester?): Modifier =
    if (requester == null) this else bringIntoViewRequester(requester)

@Composable
private fun DiscardPanel(
    view: PlayerView,
    sortHand: Boolean,
    onToggleSort: () -> Unit,
    onDiscard: (List<Card>) -> Unit,
    // Tutorial constraint: when non-null, only these cards are selectable and the discard arms
    // only once exactly they are selected.
    requiredDiscards: Set<Card>? = null,
    targets: TutorialAnchors? = null,
    peekOnAppear: Boolean = false,
    cardWidth: Dp = HandCardWidth,
) {
    var selected by remember(view.hand) { mutableStateOf(emptySet<Card>()) }
    // Guard against double taps: one discard per PlayerView.
    var acted by remember(view) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
                containerColor = CardSurfaceWhite,
                contentColor = InkOnCardSurface,
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
            peekOnAppear = peekOnAppear,
            cardWidth = cardWidth,
        )
    }
}

/** The fan's card width on a screen with room to spare; short screens scale down from here (#41). */
internal val HandCardWidth = 84.dp

/** Fan exposure: each card advances this fraction of a card width, so only that strip is visible. */
internal const val HAND_EXPOSURE = 0.45f

@Composable
private fun HumanHand(
    view: PlayerView,
    sortHand: Boolean,
    onToggleSort: () -> Unit,
    playable: (Card) -> Boolean,
    onClick: (Card) -> Unit,
    dimUnplayable: Boolean = true,
    selected: Set<Card> = emptySet(),
    targets: TutorialAnchors? = null,
    // One slow scroll to the fan's end and back when the hand first appears — shows the player the
    // full extent of a fan wider than the screen (the tutorial's kitty-exchange step uses this).
    peekOnAppear: Boolean = false,
    cardWidth: Dp = HandCardWidth,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // The sort toggle's row also carries the dealer puck when the deal is ours: this row
        // survives every layout (the panel header is the first thing the short-screen layout
        // drops), and at 30dp it is already taller than the puck, so nothing moves between hands.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(DealerButtonSize + 8.dp))
            OutlinedButton(
                onClick = onToggleSort,
                colors = feltTonalButtonColors(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp).testTag("sortToggle"),
            ) {
                Text(
                    if (sortHand) "Sorted ⇄" else "Deal order ⇄",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            // Width reserved whether or not we deal, so the toggle doesn't slide when the puck
            // arrives; the puck balances it on the other side.
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(DealerButtonSize)) { if (view.dealer == view.seat) DealerButton() }
        }
        val hand = rememberDisplayHand(view, sortHand)
        // Every card except the fan's last is mostly covered by its right neighbour: only the left
        // HAND_EXPOSURE strip shows, so that's the rect the tutorial tail should point at.
        val lastCard = hand.lastOrNull()
        // The fan is wider than the screen at this card size — scroll it horizontally.
        val scrollState = rememberScrollState()
        if (peekOnAppear) {
            LaunchedEffect(Unit) {
                // Wait for the first layout so the scroll range is known (maxValue starts at
                // Int.MAX_VALUE); if the whole fan fits on screen there is nothing to show.
                val end = snapshotFlow { scrollState.maxValue }.first { it != Int.MAX_VALUE }
                if (end > 0) {
                    scrollState.animateScrollTo(end, tween(durationMillis = 1200))
                    delay(400)
                    scrollState.animateScrollTo(0, tween(durationMillis = 1200))
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .tutorialTarget(targets, "hand"),
            contentAlignment = Alignment.Center,
        ) {
            CardHand(
                cards = hand,
                cardWidth = cardWidth,
                exposure = HAND_EXPOSURE,
                playable = playable,
                dimUnplayable = dimUnplayable,
                selected = selected,
                onCardClick = onClick,
                cardModifier = { card ->
                    Modifier.tutorialTarget(
                        targets,
                        "card:${card.label}",
                        widthFraction = if (card == lastCard) 1f else HAND_EXPOSURE,
                    )
                },
            )
        }
    }
}
