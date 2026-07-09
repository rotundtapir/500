// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.fivehundred.AnimationSpeed
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.label
import io.github.rotundtapir.fivehundred.engine.nextSeat
import io.github.rotundtapir.fivehundred.engine.teamOf

/** Amber used to make your own team's names pop against the felt (readable on the dark green). */
private val PartnerHighlight = Color(0xFFFFD54F)

/**
 * Distinct, felt-readable colours for the OPPOSING teams, assigned in team-index order (your own
 * team is always the amber [PartnerHighlight]). Drawn from the Okabe–Ito palette so the amber /
 * blue / purple triad stays distinguishable under all common colour-vision deficiencies (the
 * red↔green axis is avoided). Telling teams apart by colour matters most in the 6-player,
 * three-teams-of-two game, where five other names crowd the table.
 */
private val OpponentTeamColors = listOf(
    Color(0xFF56B4E9), // sky blue      (Okabe–Ito)
    Color(0xFFCC79A7), // reddish purple (Okabe–Ito)
)

/**
 * The colour a [seat]'s name is drawn in: your own team in amber, each opposing team a distinct
 * hue. Opposing teams are coloured by their order among the *other* teams (skipping yours), so the
 * palette is used from its start regardless of which team index you were dealt into. Returns
 * [Color.Unspecified] (inherit) if the palette runs out — it never does at the supported counts.
 */
private fun teamColor(view: PlayerView, seat: Seat): Color {
    val team = teamOf(seat, view.teamCount)
    if (team == view.myTeam) return PartnerHighlight
    val idx = (0 until view.teamCount).filter { it != view.myTeam }.indexOf(team)
    return OpponentTeamColors.getOrElse(idx) { Color.Unspecified }
}

/** Clickable only while [enabled] — written as a factory to avoid conditional `.then` chains. */
private fun Modifier.tappableWhen(enabled: Boolean, onTap: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onTap) else this

@Composable
internal fun ScoreBar(
    view: PlayerView,
    botNames: Map<Seat, String>,
    onOpenSettings: () -> Unit,
    onMenu: () -> Unit,
) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("gameSettingsButton"),
            ) {
                Icon(
                    imageVector = SettingsIcon,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            TextButton(
                onClick = onMenu,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
            ) { Text("Menu") }
        }
    }
}

@Composable
internal fun ContractLine(view: PlayerView, botNames: Map<Seat, String>) {
    val text = when {
        view.contract != null -> "Contract: ${seatLabel(view, botNames, view.contract!!.declarer)} · ${view.contract!!.bid.label}"
        view.phase == Phase.BIDDING -> "Bidding" + (view.highBid?.let { " · high: ${it.label}" } ?: "")
        else -> ""
    }
    val last = view.lastHandResult?.let {
        "  (last: ${it.contract.bid.label} ${if (it.made) "made" else "failed"})"
    } ?: ""
    SuitText(text + last)
}

@Composable
internal fun OpponentsRow(view: PlayerView, botNames: Map<Seat, String>, dealState: DealAnimationState) {
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
        // Colour each name by its team so sides read at a glance across the table: your own team in
        // amber (your partner's tricks are your tricks), each opposing team its own hue. Your team
        // is always bold too, so it stands out even where the palette can't (e.g. mono displays).
        val isPartner = teamOf(seat, view.teamCount) == view.myTeam
        val nameColor = teamColor(view, seat)
        Text(
            seatLabel(view, botNames, seat),
            style = textStyle,
            color = nameColor,
            fontWeight = if (view.toAct == seat || isPartner) FontWeight.Bold else FontWeight.Normal,
        )
        if (isPartner) {
            Text("(partner)", style = MaterialTheme.typography.labelSmall, color = nameColor)
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
            SuitText(
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
            SuitText(
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
internal fun ExposedDeclarerHand(view: PlayerView, botNames: Map<Seat, String>) {
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
internal fun TrickArea(
    view: PlayerView,
    botNames: Map<Seat, String>,
    animationSpeed: AnimationSpeed,
    dealState: DealAnimationState,
    modifier: Modifier = Modifier,
    holdTricks: Boolean = false,
    // Forces the hold on regardless of the setting — the tutorial uses this so every completed
    // trick waits to be explained.
    forceHold: Boolean = false,
    onTrickAcknowledged: (Int, Int) -> Unit = { _, _ -> },
) {
    // With "Hold completed tricks" on (in settings), a completed trick stays on the felt until the
    // player taps it away — time to memorise the cards for counting.
    val holdingTrick = (holdTricks || forceHold) &&
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
    // Lay the trick out in its FINAL geometry from the first card: one slot per active seat, in
    // play order, with the still-to-play seats as faint placeholders. Cards land in place and
    // never shift as later plays arrive (before this, a six-player trick reflowed from one row
    // into two when the fifth card landed).
    val played = plays.map { it.seat }.toSet()
    val upcoming = generateSequence(nextSeat(plays.last().seat, view.playerCount)) { nextSeat(it, view.playerCount) }
        .take(view.playerCount)
        .filter { it in view.activeSeats && it !in played }
        .toList()
    val slots: List<Pair<Seat, TrickPlay?>> =
        plays.map { it.seat to it } + upcoming.map { it to null }
    // Six-player tricks don't fit in one row on a phone — split into two rows (3+3, or 3+2 in
    // Misère) so the cards stay full size.
    val rows = if (slots.size <= 4) listOf(slots) else slots.chunked((slots.size + 1) / 2)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { rowSlots ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowSlots.forEach { (seat, play) ->
                    if (play != null) {
                        TrickPlayCell(view, botNames, play)
                    } else {
                        EmptyTrickSlot(view, botNames, seat)
                    }
                }
            }
        }
    }
}

/** A yet-to-play seat's slot in the trick: a card-sized outline over a dimmed seat name. */
@Composable
private fun EmptyTrickSlot(view: PlayerView, botNames: Map<Seat, String>, seat: Seat) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            // Mirrors PlayingCard's geometry at width 56.dp (height ×1.4, corner radius ×0.08)
            // so the slot reserves exactly the space the card will take.
            modifier = Modifier
                .size(56.dp, 56.dp * 1.4f)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f),
                    RoundedCornerShape(56.dp * 0.08f),
                ),
        )
        Spacer(Modifier.height(4.dp))
        // Team-tinted like the played cells, but dimmed — this seat hasn't played yet.
        val base = teamColor(view, seat)
        Text(
            seatLabel(view, botNames, seat),
            maxLines = 1,
            color = if (base == Color.Unspecified) {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            } else {
                base.copy(alpha = 0.55f)
            },
        )
    }
}

@Composable
private fun TrickPlayCell(view: PlayerView, botNames: Map<Seat, String>, play: TrickPlay) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PlayingCard(play.card, width = 56.dp)
        Spacer(Modifier.height(4.dp))
        // Colour the name by team so you can read whose card this is at a glance — your own team
        // (you and your partner) in amber and bold, each opposing team its own hue.
        val isMyTeam = teamOf(play.seat, view.teamCount) == view.myTeam
        Text(
            seatLabel(view, botNames, play.seat),
            maxLines = 1,
            color = teamColor(view, play.seat),
            fontWeight = if (isMyTeam) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
