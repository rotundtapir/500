// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.ui.CardBack
import io.github.rotundtapir.cardkit.ui.CardHand
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.Trump
import io.github.rotundtapir.fivehundred.engine.label
import io.github.rotundtapir.fivehundred.engine.nextSeat
import io.github.rotundtapir.fivehundred.engine.partnerOf
import io.github.rotundtapir.fivehundred.engine.teamOf

private fun seatLabel(view: PlayerView, botNames: Map<Seat, String>, seat: Seat): String = when {
    seat == view.seat -> "You"
    else -> botNames[seat] ?: when (seat) {
        partnerOf(view.seat) -> "Partner"
        nextSeat(view.seat) -> "Left"
        else -> "Right"
    }
}

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
    monetization: Monetization,
    activity: Activity,
    onBid: (Bid) -> Unit,
    onDiscard: (List<Card>) -> Unit,
    onPlay: (Card) -> Unit,
    onExit: () -> Unit,
) {
    var sortHand by rememberSaveable { mutableStateOf(true) }
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
            ContractLine(view, botNames)
            Spacer(Modifier.height(12.dp))
            OpponentsRow(view, botNames)
            TrickArea(view, botNames, modifier = Modifier.weight(1f))
            ActionArea(
                view = view,
                botNames = botNames,
                sortHand = sortHand,
                onToggleSort = { sortHand = !sortHand },
                onBid = onBid,
                onDiscard = onDiscard,
                onPlay = onPlay,
            )
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

    HandResultDialog(view, botNames)
}

@Composable
private fun HandResultDialog(view: PlayerView, botNames: Map<Seat, String>) {
    val result = view.lastHandResult ?: return
    if (view.winner != null) return // the winner dialog handles game end
    var dismissed by remember(view.lastHandResult) { mutableStateOf(false) }
    if (dismissed) return

    val contract = result.contract
    val declarerTeam = teamOf(contract.declarer)
    val onDeclarerSide = teamOf(view.seat) == declarerTeam
    val declarerSideLabel = if (onDeclarerSide) "Us" else "Them"
    val defenderSideLabel = if (onDeclarerSide) "Them" else "Us"
    val declarerDelta = result.teamDeltas[declarerTeam] ?: 0
    val defenderDelta = result.teamDeltas[1 - declarerTeam] ?: 0
    val defenderTricks = 10 - result.declarerTricks

    val bidLine = buildString {
        append("${seatLabel(view, botNames, contract.declarer)} bid ${contract.bid.label}")
        if (!contract.isMisere) append(" — needed ${contract.level} tricks")
    }
    val tricksLine = if (contract.isMisere) {
        "Declarer took ${result.declarerTricks} tricks (needed none)"
    } else {
        "Declarer's side took ${result.declarerTricks} of 10 tricks"
    }
    val declarerSideLine =
        "$declarerSideLabel: ${signed(declarerDelta)} (${if (result.made) "contract made" else "contract failed"})"
    val defenderSideLine = if (contract.isMisere) {
        "$defenderSideLabel: +0"
    } else {
        "$defenderSideLabel: ${signed(defenderDelta)} ($defenderTricks ${if (defenderTricks == 1) "trick" else "tricks"} × 10)"
    }

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(if (result.made) "Contract made!" else "Contract failed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(bidLine)
                Text(tricksLine)
                Text(declarerSideLine)
                Text(defenderSideLine)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { dismissed = true },
                modifier = Modifier.testTag("handResultContinue"),
            ) { Text("Continue") }
        },
    )
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
private fun OpponentsRow(view: PlayerView, botNames: Map<Seat, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (i in 0 until 4) {
            val seat = Seat(i)
            if (seat == view.seat) continue
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val active = seat in view.activeSeats
                Text(
                    seatLabel(view, botNames, seat),
                    fontWeight = if (view.toAct == seat) FontWeight.Bold else FontWeight.Normal,
                )
                if (seat == partnerOf(view.seat)) {
                    Text("(partner)", style = MaterialTheme.typography.labelSmall)
                }
                if (active) CardBack(width = 32.dp) else Text("(sitting out)")
                Text("cards: ${view.handSizes[seat] ?: 0}")
                Text("tricks: ${view.tricksWon[seat] ?: 0}")
                if (view.phase == Phase.BIDDING) {
                    val lastAction = view.biddingHistory.lastOrNull { it.first == seat }?.second
                    Text(
                        when {
                            lastAction == null -> "—"
                            lastAction == Bid.Pass -> "passed"
                            else -> "bid ${lastAction.label}"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrickArea(view: PlayerView, botNames: Map<Seat, String>, modifier: Modifier = Modifier) {
    // The "felt" — a slightly darker rounded table centre where the current trick lands.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(Color(0x22000000), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
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

@Composable
private fun TrickPlaysRow(view: PlayerView, botNames: Map<Seat, String>, plays: List<TrickPlay>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        plays.forEach { play ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlayingCard(play.card, width = 56.dp)
                Spacer(Modifier.height(4.dp))
                Text(seatLabel(view, botNames, play.seat))
            }
        }
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
) {
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
                BiddingPanel(view, onBid)
                HumanHand(view, sortHand, onToggleSort, playable = { false }, dimUnplayable = false, onClick = {})
            }
            view.phase == Phase.KITTY && view.mustDiscard > 0 -> {
                DiscardPanel(view, sortHand, onToggleSort, onDiscard)
            }
            view.phase == Phase.PLAY && view.isMyTurn -> {
                Text("Your turn — tap a card to play")
                Spacer(Modifier.height(4.dp))
                HumanHand(view, sortHand, onToggleSort, playable = { it in view.legalPlays }, onClick = onPlay)
            }
            else -> {
                Text(view.toAct?.let { "Waiting for ${seatLabel(view, botNames, it)}…" } ?: "")
                Spacer(Modifier.height(4.dp))
                HumanHand(view, sortHand, onToggleSort, playable = { false }, dimUnplayable = false, onClick = {})
            }
        }
    }
}

@Composable
private fun BiddingPanel(view: PlayerView, onBid: (Bid) -> Unit) {
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
                enabled = !acted,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)),
                modifier = Modifier.testTag("bid:${bid.label}"),
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
) {
    var selected by remember(view.hand) { mutableStateOf(emptySet<Card>()) }
    // Guard against double taps: one discard per PlayerView.
    var acted by remember(view) { mutableStateOf(false) }
    Text("Discard $KITTY_SIZE cards to the kitty (${selected.size}/$KITTY_SIZE selected)", fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Button(
        onClick = {
            acted = true
            onDiscard(selected.toList())
        },
        enabled = selected.size == KITTY_SIZE && !acted,
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
    sortHand: Boolean,
    onToggleSort: () -> Unit,
    playable: (Card) -> Boolean,
    onClick: (Card) -> Unit,
    dimUnplayable: Boolean = true,
    selected: Set<Card> = emptySet(),
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onToggleSort,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.testTag("sortToggle"),
        ) { Text(if (sortHand) "Sorted" else "Deal order", style = MaterialTheme.typography.labelMedium) }
        val hand = if (sortHand) sortedForDisplay(view.hand, view.trump) else view.hand
        // The fan is wider than the screen at this card size — scroll it horizontally.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
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
            )
        }
    }
}
