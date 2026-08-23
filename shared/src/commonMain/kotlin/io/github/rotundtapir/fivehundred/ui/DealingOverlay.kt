// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.cardkit.core.Card
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.ui.CardBack
import io.github.rotundtapir.cardkit.ui.deal.DealAnimationState
import io.github.rotundtapir.cardkit.ui.deal.DealPacket
import io.github.rotundtapir.cardkit.ui.deal.DealStage
import io.github.rotundtapir.cardkit.ui.deal.DealTarget
import io.github.rotundtapir.cardkit.ui.deal.ShufflingDeck
import io.github.rotundtapir.cardkit.ui.deal.dealAnchor

// 500's face of the shared deal animation (cardkit-ui.deal): the schedule below encodes 500's true
// packet order, and the felt-centre composables (kitty pile, deck header) are game-specific. The
// generic machinery — DealAnimationState, runDealAnimation, the flying packet, the opponents'
// piles and the human's flipping row — lives in cardkit.

/** The kitty's destination/anchor on the felt, shared by the schedule and the pile below. */
internal val KittyTarget = DealTarget.Center("kitty")

/**
 * 500's deal, in its rules' true packet order: a visible packet to each seat starting left of the
 * dealer (the dealer last), then a single card to the kitty after each full round — packets of 3,
 * then 4, then 3, making 10 cards per hand and a 3-card kitty.
 */
internal fun fiveHundredDealSchedule(playerCount: Int, dealer: Seat): List<DealPacket> = buildList {
    val seats = (1..playerCount).map { Seat((dealer.index + it) % playerCount) }
    for (packet in intArrayOf(3, 4, 3)) {
        for (seat in seats) add(DealPacket(DealTarget.SeatPile(seat), packet))
        add(DealPacket(KittyTarget, 1))
    }
}

// Kitty cards match the trick cards' width (TrickArea computes it from the felt; 56dp is the
// floor) and sit side by side like a trick — overlapped backs just read as one wide card.
private val KittyCardWidth = 56.dp
private val KittyCardGap = 6.dp

/**
 * The [count] face-down kitty cards, trick-sized and side by side, growing centred as they land.
 *
 * [faceUp] is the cheat path (#51): with all four hands revealed the kitty is just the complement
 * of what you can already see, so keeping it face down is friction rather than secrecy. Empty in
 * every normal game, and necessarily empty online.
 */
@Composable
internal fun KittyPile(
    count: Int,
    modifier: Modifier = Modifier,
    cardWidth: Dp = KittyCardWidth,
    faceUp: List<Card> = emptyList(),
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Spacer(Modifier.size(cardWidth * 3 + KittyCardGap * 2, cardWidth * 1.4f))
            Row(horizontalArrangement = Arrangement.spacedBy(KittyCardGap)) {
                if (faceUp.isEmpty()) {
                    repeat(count) { CardBack(width = cardWidth) }
                } else {
                    faceUp.forEach { card -> PlayingCard(card, width = cardWidth) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Kitty", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Felt centre while a hand is being dealt (and just after): the deck the cards fly out of, plus
 * the growing kitty pile. The deck header collapses once the last card lands, leaving the kitty
 * where the plain bidding-phase kitty renders.
 */
@Composable
internal fun DealFelt(state: DealAnimationState, kittyCardWidth: Dp = KittyCardWidth) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = state.stage == DealStage.SHUFFLING || state.stage == DealStage.DEALING,
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (state.stage == DealStage.SHUFFLING) "Shuffling…" else "Dealing…",
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                ShufflingDeck(state)
                Spacer(Modifier.height(20.dp))
            }
        }
        KittyPile(
            count = state.countFor(KittyTarget),
            modifier = Modifier.dealAnchor(state, KittyTarget),
            cardWidth = kittyCardWidth,
        )
    }
}
