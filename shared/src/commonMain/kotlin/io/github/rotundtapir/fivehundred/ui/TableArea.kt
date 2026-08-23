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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.ui.CardAspectRatio
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.cardkit.ui.SettingsIcon
import io.github.rotundtapir.cardkit.ui.SuitText
import io.github.rotundtapir.cardkit.ui.cardFaceShape
import io.github.rotundtapir.cardkit.ui.deal.DealAnimationState
import io.github.rotundtapir.cardkit.ui.deal.OpponentPile
import io.github.rotundtapir.cardkit.ui.clickableWhen
import io.github.rotundtapir.cardkit.ui.felt.CardSurfaceWhite
import io.github.rotundtapir.cardkit.ui.felt.OnBackgroundIconButton
import io.github.rotundtapir.cardkit.ui.felt.OpponentTeamColors
import io.github.rotundtapir.cardkit.ui.felt.PartnerHighlight
import io.github.rotundtapir.cardkit.ui.settings.AnimationSpeed
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialAnchors
import io.github.rotundtapir.cardkit.ui.tutorial.tutorialTarget
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.label
import io.github.rotundtapir.fivehundred.engine.nextSeat
import io.github.rotundtapir.fivehundred.engine.teamOf

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

@Composable
internal fun ScoreBar(
    view: PlayerView,
    botNames: Map<Seat, String>,
    onOpenSettings: () -> Unit,
    onMenu: () -> Unit,
    // Online games slot an emote control in here (next to Settings) so it lives in the top bar
    // rather than overlaying the hand / ad / nav at the bottom.
    trailing: (@Composable () -> Unit)? = null,
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
            trailing?.invoke()
            OnBackgroundIconButton(
                imageVector = SettingsIcon,
                contentDescription = "Settings",
                onClick = onOpenSettings,
                modifier = Modifier.testTag("gameSettingsButton"),
            )
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
    val full = text + last
    if (full.isBlank()) {
        Text("") // keep the row's height stable between phases
    } else {
        // A card-white pill: the line carries bid labels whose black suit symbols sink into the
        // felt (2026-07-11 contrast audit) — the same treatment as the enabled bid buttons.
        Surface(
            shape = RoundedCornerShape(50),
            color = CardSurfaceWhite,
            contentColor = Color(0xFF1B1B1B),
        ) {
            SuitText(full, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
        }
    }
}

@Composable
internal fun OpponentsRow(
    view: PlayerView,
    botNames: Map<Seat, String>,
    dealState: DealAnimationState,
    seatAnchors: TutorialAnchors? = null,
    // True when the screen is too short for full-size chrome (landscape phone, small window): the
    // row uses its compact form so the height it gives up goes to the hand instead (#41).
    shortScreen: Boolean = false,
) {
    // With 5 opponents (6-player game) the row gets tight: shrink each column and allow the row to
    // scroll horizontally as a safety valve on narrow screens. A short screen compacts for height
    // rather than width, so it takes the smaller text and pile without the horizontal scroll.
    val crowded = view.playerCount == 6
    val compact = crowded || shortScreen
    val rowModifier = if (crowded) {
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier,
        horizontalArrangement = if (crowded) Arrangement.spacedBy(12.dp) else Arrangement.SpaceEvenly,
    ) {
        // Order opponents going clockwise from the local player, so the seating reads like a real
        // table: your partner lands opposite (in the middle) and the two other-team seats flank it,
        // matching the turn order shown in the trick area. (Raw seat order put the partner first,
        // which hid that you sit between the two opponents.)
        for (offset in 1 until view.playerCount) {
            val seat = Seat((view.seat.index + offset) % view.playerCount)
            // Equal-width columns so a long name (e.g. a bot-substituted human's "Name (bot)") is
            // ellipsised within its slot instead of stretching it and squishing the others. Compact
            // (6-player) keeps its fixed-width scrolling columns.
            val columnModifier = if (crowded) Modifier else Modifier.weight(1f)
            OpponentStatus(view, botNames, seat, compact, dealState, columnModifier, seatAnchors)
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
    modifier: Modifier = Modifier,
    seatAnchors: TutorialAnchors? = null,
) {
    val textStyle = if (compact) MaterialTheme.typography.bodySmall else LocalTextStyle.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.tutorialTarget(seatAnchors, "seat:${seat.index}"),
    ) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
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
 * The opponents as a narrow vertical list — the landscape/short-screen counterpart of
 * [OpponentsRow]. Trading the piles and the stacked cards/tricks lines for one line per seat is
 * what frees the height a landscape phone needs for the felt and the player's own hand (#41).
 */
@Composable
internal fun OpponentsColumn(
    view: PlayerView,
    botNames: Map<Seat, String>,
    dealState: DealAnimationState,
    modifier: Modifier = Modifier,
    seatAnchors: TutorialAnchors? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (offset in 1 until view.playerCount) {
            val seat = Seat((view.seat.index + offset) % view.playerCount)
            val isPartner = teamOf(seat, view.teamCount) == view.myTeam
            val active = seat in view.activeSeats
            val cardCount = if (dealState.dealing) dealState.dealtTo(seat) else view.handSizes[seat] ?: 0
            Column(modifier = Modifier.tutorialTarget(seatAnchors, "seat:${seat.index}")) {
                Text(
                    seatLabel(view, botNames, seat) + if (isPartner) " (partner)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = teamColor(view, seat),
                    fontWeight = if (view.toAct == seat || isPartner) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SuitText(
                    if (active) {
                        "$cardCount cards · ${view.tricksWon[seat] ?: 0} tricks${seatBidSuffix(view, seat)}"
                    } else {
                        "sitting out"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** " · passed" / " · bid 7♠" for the auction and kitty phases; empty once play starts. */
private fun seatBidSuffix(view: PlayerView, seat: Seat): String = when (view.phase) {
    Phase.BIDDING -> when (val last = view.biddingHistory.lastOrNull { it.first == seat }?.second) {
        null -> ""
        Bid.Pass -> " · passed"
        else -> " · bid ${last.label}"
    }
    Phase.KITTY -> view.biddingHistory
        .lastOrNull { it.first == seat && it.second != Bid.Pass }?.second
        ?.let { " · bid ${it.label}" } ?: ""
    else -> ""
}

/**
 * During an open-misère PLAY phase every defender sees the declarer's exposed hand; render it as a
 * labelled row of small face-up cards above the trick area. Null (and absent) for the declarer.
 */
@Composable
internal fun ExposedDeclarerHand(
    view: PlayerView,
    botNames: Map<Seat, String>,
    // Landscape renders this in the side panel, where the cards must be smaller and the caption
    // tighter — otherwise the exposed row eats the felt it is supposed to sit beside (#41).
    compact: Boolean = false,
) {
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
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
        ) {
            exposed.forEach { card -> PlayingCard(card, width = if (compact) 26.dp else 40.dp) }
        }
    }
}

/**
 * Cheat-only (#51): every other seat's hand, face up. Same presentation as [ExposedDeclarerHand] —
 * the renderer is worth reusing — but a completely different data path: these cards come from the
 * caller's local `GameState`, never from [PlayerView], so an online client has nothing to show and
 * `PlayerView` stays un-widened.
 */
@Composable
internal fun RevealedHands(
    view: PlayerView,
    botNames: Map<Seat, String>,
    hands: Map<Seat, List<Card>>,
    compact: Boolean = false,
) {
    if (hands.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Filtered first rather than skipped inside the loop: two `continue`s in one body reads
        // worse than one comprehension (and detekt agrees).
        val others = view.activeSeats.filter { it != view.seat }.mapNotNull { seat ->
            hands[seat]?.let { seat to it }
        }
        for ((seat, hand) in others) {
            FaceUpHandRow(seatLabel(view, botNames, seat), hand, compact)
        }
    }
}

/** A labelled row of small face-up cards — the shape both the open-misère and cheat reveals want. */
@Composable
private fun FaceUpHandRow(label: String, cards: List<Card>, compact: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
        ) {
            cards.forEach { card -> PlayingCard(card, width = if (compact) 22.dp else 32.dp) }
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
    // The tutorial's guidance bubble carries its own "tap the trick" instruction and can sit over
    // the felt's hint — this hides only the hint text, never the hold itself (the hold decision
    // arrives fully formed in [holdTricks]).
    hideTapHint: Boolean = false,
    onTrickAcknowledge: (Int, Int) -> Unit = { _, _ -> },
    // A short screen (landscape phone) lets the felt's cards go below the usual floor rather than
    // overflow the felt they sit on (#41).
    shortScreen: Boolean = false,
    // Cheat-only (#51): the kitty's actual cards, shown face up on the felt. Empty in every normal
    // game — and the online client never holds them.
    revealedKitty: List<Card> = emptyList(),
) {
    // With the hold on (settings toggle, or the tutorial forcing it — decided by the caller), a
    // completed trick stays on the felt until the player taps it away.
    val holdingTrick = holdTricks &&
        animationSpeed != AnimationSpeed.OFF &&
        !dealState.dealing &&
        view.hasClosedTrick()
    // The "felt" — a slightly darker rounded table centre where the current trick lands.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(Color(0x22000000), RoundedCornerShape(16.dp))
            .clickableWhen(holdingTrick) { onTrickAcknowledge(view.handNumber, view.trickNumber) },
        contentAlignment = Alignment.Center,
    ) {
        // Size the cards on the felt from the felt itself: a full trick (with its name labels and
        // the won-trick caption) must fit, but beyond that use the space — the old fixed 56dp left
        // most of the felt empty. 56dp stays the floor so cramped layouts never regress.
        val seats = view.activeSeats.size
        val rows = if (seats <= 4) 1 else 2
        val perRow = if (rows == 1) seats else (seats + 1) / 2
        val byWidth = (maxWidth - 16.dp - 8.dp * (perRow - 1)) / perRow
        val byHeight = (maxHeight - 48.dp - 26.dp * rows - 6.dp * (rows - 1)) / rows / CardAspectRatio
        val cardWidth = minOf(byWidth, byHeight).coerceIn(if (shortScreen) 36.dp else 56.dp, 96.dp)
        if (dealState.dealing) {
            // Deck + growing kitty pile while cards fly out.
            DealFelt(dealState, cardWidth)
        } else if (view.phase == Phase.BIDDING) {
            // The kitty sits face down on the felt for the whole auction (all speeds, incl. OFF);
            // it leaves the table once the contract is decided.
            KittyPile(count = KITTY_SIZE, cardWidth = cardWidth, faceUp = revealedKitty)
        } else {
            AnimatedContent(
                targetState = view,
                contentKey = { it.currentTrick.size to it.trickNumber },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "trickArea",
            ) { v ->
                val lastTrick = v.lastTrick
                when {
                    v.currentTrick.isNotEmpty() -> TrickPlaysRow(v, botNames, v.currentTrick, cardWidth)
                    lastTrick != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TrickPlaysRow(v, botNames, lastTrick.plays, cardWidth)
                        Spacer(Modifier.height(4.dp))
                        Text("${seatLabel(v, botNames, lastTrick.winner)} won the trick")
                        if (holdingTrick && !hideTapHint) {
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
private fun TrickPlaysRow(view: PlayerView, botNames: Map<Seat, String>, plays: List<TrickPlay>, cardWidth: Dp) {
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
                        TrickPlayCell(view, botNames, play, cardWidth)
                    } else {
                        EmptyTrickSlot(view, botNames, seat, cardWidth)
                    }
                }
            }
        }
    }
}

/** A yet-to-play seat's slot in the trick: a card-sized outline over a dimmed seat name. */
@Composable
private fun EmptyTrickSlot(view: PlayerView, botNames: Map<Seat, String>, seat: Seat, cardWidth: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            // Mirrors PlayingCard's geometry so the slot reserves exactly the space the card
            // will take, staying in step if the card shape ever changes.
            modifier = Modifier
                .size(cardWidth, cardWidth * CardAspectRatio)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.18f),
                    cardFaceShape(cardWidth),
                ),
        )
        Spacer(Modifier.height(4.dp))
        // Team-tinted like the played cells, but dimmed — this seat hasn't played yet.
        val base = teamColor(view, seat)
        Text(
            seatLabel(view, botNames, seat),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = cardWidth + NAME_OVERHANG),
            color = if (base == Color.Unspecified) {
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
            } else {
                base.copy(alpha = 0.55f)
            },
        )
    }
}

@Composable
private fun TrickPlayCell(view: PlayerView, botNames: Map<Seat, String>, play: TrickPlay, cardWidth: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PlayingCard(play.card, width = cardWidth)
        Spacer(Modifier.height(4.dp))
        // Colour the name by team so you can read whose card this is at a glance — your own team
        // (you and your partner) in amber and bold, each opposing team its own hue.
        val isMyTeam = teamOf(play.seat, view.teamCount) == view.myTeam
        Text(
            seatLabel(view, botNames, play.seat),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = cardWidth + NAME_OVERHANG),
            color = teamColor(view, play.seat),
            fontWeight = if (isMyTeam) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** How far a played-card's name label may overhang its card before it ellipsizes. */
private val NAME_OVERHANG = 16.dp
