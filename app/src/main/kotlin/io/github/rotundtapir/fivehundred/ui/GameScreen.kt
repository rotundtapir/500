// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.ui.CardBack
import io.github.rotundtapir.cardkit.ui.CardHand
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.label
import io.github.rotundtapir.fivehundred.engine.nextSeat
import io.github.rotundtapir.fivehundred.engine.partnerOf
import io.github.rotundtapir.fivehundred.engine.teamOf

private fun seatLabel(view: PlayerView, seat: Seat): String = when (seat) {
    view.seat -> "You"
    partnerOf(view.seat) -> "Partner"
    nextSeat(view.seat) -> "Left"
    else -> "Right"
}

@Composable
fun GameScreen(
    view: PlayerView,
    monetization: Monetization,
    activity: Activity,
    onBid: (Bid) -> Unit,
    onDiscard: (List<Card>) -> Unit,
    onPlay: (Card) -> Unit,
    onExit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp),
        ) {
            ScoreBar(view, onExit)
            ContractLine(view)
            Spacer(Modifier.height(12.dp))
            OpponentsRow(view)
            TrickArea(view, modifier = Modifier.weight(1f))
            ActionArea(view, onBid, onDiscard, onPlay)
            Spacer(Modifier.height(8.dp))
            monetization.BannerSlot(Modifier.fillMaxWidth())
        }
    }

    view.winner?.let { winningTeam ->
        val youWon = winningTeam == teamOf(view.seat)
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (youWon) "You win!" else "You lose") },
            text = { Text("Final score — you ${view.scores[teamOf(view.seat)]}, opponents ${view.scores[1 - teamOf(view.seat)]}") },
            confirmButton = { TextButton(onClick = onExit) { Text("Back to menu") } },
        )
    }
}

@Composable
private fun ScoreBar(view: PlayerView, onExit: () -> Unit) {
    val myTeam = teamOf(view.seat)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Us: ${view.scores[myTeam] ?: 0}", fontWeight = FontWeight.Bold)
        Text("Them: ${view.scores[1 - myTeam] ?: 0}", fontWeight = FontWeight.Bold)
        TextButton(
            onClick = onExit,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
        ) { Text("Menu") }
    }
}

@Composable
private fun ContractLine(view: PlayerView) {
    val text = when {
        view.contract != null -> "Contract: ${seatLabel(view, view.contract!!.declarer)} · ${view.contract!!.bid.label}"
        view.phase == Phase.BIDDING -> "Bidding" + (view.highBid?.let { " · high: ${it.label}" } ?: "")
        else -> ""
    }
    val last = view.lastHandResult?.let {
        "  (last: ${it.contract.bid.label} ${if (it.made) "made" else "failed"})"
    } ?: ""
    Text(text + last)
}

@Composable
private fun OpponentsRow(view: PlayerView) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (i in 0 until 4) {
            val seat = Seat(i)
            if (seat == view.seat) continue
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val active = seat in view.activeSeats
                Text(seatLabel(view, seat), fontWeight = if (view.toAct == seat) FontWeight.Bold else FontWeight.Normal)
                if (active) CardBack(width = 32.dp) else Text("(sitting out)")
                Text("cards: ${view.handSizes[seat] ?: 0}")
                Text("tricks: ${view.tricksWon[seat] ?: 0}")
            }
        }
    }
}

@Composable
private fun TrickArea(view: PlayerView, modifier: Modifier = Modifier) {
    // The "felt" — a slightly darker rounded table centre where the current trick lands.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(Color(0x22000000), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (view.currentTrick.isEmpty()) {
            val hint = when {
                view.phase == Phase.PLAY && view.isMyTurn -> "You lead"
                view.phase == Phase.PLAY -> "Waiting for the first card…"
                else -> ""
            }
            Text(hint)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                view.currentTrick.forEach { play ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PlayingCard(play.card, width = 56.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(seatLabel(view, play.seat))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionArea(
    view: PlayerView,
    onBid: (Bid) -> Unit,
    onDiscard: (List<Card>) -> Unit,
    onPlay: (Card) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // "You" mirror of the opponents' status line.
        Text(
            "You — tricks: ${view.tricksWon[view.seat] ?: 0}",
            fontWeight = if (view.isMyTurn) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(4.dp))
        when {
            view.phase == Phase.BIDDING && view.isMyTurn -> {
                BiddingPanel(view.legalBids, onBid)
                HumanHand(view, playable = { false }, dimUnplayable = false, onClick = {})
            }
            view.phase == Phase.KITTY && view.mustDiscard > 0 -> {
                DiscardPanel(view, onDiscard)
            }
            view.phase == Phase.PLAY && view.isMyTurn -> {
                Text("Your turn — tap a card to play")
                Spacer(Modifier.height(4.dp))
                HumanHand(view, playable = { it in view.legalPlays }, onClick = onPlay)
            }
            else -> {
                Text(view.toAct?.let { "Waiting for ${seatLabel(view, it)}…" } ?: "")
                Spacer(Modifier.height(4.dp))
                HumanHand(view, playable = { false }, dimUnplayable = false, onClick = {})
            }
        }
    }
}

@Composable
private fun BiddingPanel(legalBids: List<Bid>, onBid: (Bid) -> Unit) {
    Text("Your bid:", fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Pass first, then the ranked contracts.
        legalBids.sortedBy { it != Bid.Pass }.forEach { bid ->
            OutlinedButton(
                onClick = { onBid(bid) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)),
                modifier = Modifier.testTag("bid:${bid.label}"),
            ) { Text(bid.label) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DiscardPanel(view: PlayerView, onDiscard: (List<Card>) -> Unit) {
    var selected by remember(view.hand) { mutableStateOf(emptySet<Card>()) }
    Text("Discard $KITTY_SIZE cards to the kitty (${selected.size}/$KITTY_SIZE selected)", fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = { onDiscard(selected.toList()) },
        enabled = selected.size == KITTY_SIZE,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFAFAFA),
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.testTag("discardButton"),
    ) { Text("Discard") }
    Spacer(Modifier.height(4.dp))
    HumanHand(
        view = view,
        playable = { true },
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
    playable: (Card) -> Boolean,
    onClick: (Card) -> Unit,
    dimUnplayable: Boolean = true,
    selected: Set<Card> = emptySet(),
) {
    // The fan is wider than the screen at this card size — scroll it horizontally.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        CardHand(
            cards = view.hand,
            cardWidth = 84.dp,
            overlap = 0.45f,
            playable = playable,
            dimUnplayable = dimUnplayable,
            selected = selected,
            onCardClick = onClick,
        )
    }
}
