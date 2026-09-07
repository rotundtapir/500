// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.cardkit.ui.SuitText
import io.github.rotundtapir.cardkit.ui.felt.InkOnCardSurface
import io.github.rotundtapir.cardkit.ui.tutorial.CardFaceDialog
import io.github.rotundtapir.cardkit.ui.tutorial.NarrationState
import io.github.rotundtapir.cardkit.ui.tutorial.ReaderTextButton
import io.github.rotundtapir.cardkit.ui.tutorial.ReaderTitle
import io.github.rotundtapir.cardkit.ui.tutorial.TutorialPagesDialog
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.ScoreSchedule
import io.github.rotundtapir.fivehundred.engine.Trump

// The "card face" reading surface (CardFaceDialog, ReaderTitle, ReaderTextButton, the paged
// TutorialPagesDialog) lives in cardkit-ui (io.github.rotundtapir.cardkit.ui.tutorial); what
// follows is 500's content on it: the tutorial primer and the rules reference.

/** Faint green for dividers and the bid table's ground. */
private val ReaderTint = Color(0xFFE7F0E7)

// ------------------------------------------------------------------------------------------------
// Tutorial primer (home screen → "How to play" → these pages → the interactive scripted hand)
// ------------------------------------------------------------------------------------------------

/**
 * The paged primer shown before the interactive tutorial (see Tutorial.kt) deals its practice
 * hand: aim of the game, tricks, bidding and the kitty, hand valuation, then the hand-over page
 * whose Start button deals.
 */
@Composable
fun TutorialIntroDialog(
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    narration: NarrationState? = null,
) {
    TutorialPagesDialog(
        pages = tutorialPrologue,
        nextTag = "tutorialIntroNext",
        finishLabel = "Start",
        finishTag = "tutorialStart",
        onFinish = onStart,
        onDismiss = onDismiss,
        uniformBodyHeight = true,
        narration = narration,
        narrationUriFor = ::narrationUriFor,
    )
}

// ------------------------------------------------------------------------------------------------
// Rules reference (settings → "Help")
// ------------------------------------------------------------------------------------------------

/** The comprehensive rules of 500 as implemented, opened from the settings dialog. */
@Composable
fun RulesDialog(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    CardFaceDialog(onDismissRequest = onDismiss, modifier = modifier) {
        ReaderTitle("Rules of 500", modifier = Modifier.padding(horizontal = 24.dp).padding(top = 22.dp))
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = ReaderTint, thickness = 2.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            RuleSection(
                "Objective",
                "500 is a partnership trick-taking game. The first side to reach +500 points " +
                    "by making its own contract wins the game. A side that falls to −500 " +
                    "points loses immediately (\"out the back door\"). Points scored by the " +
                    "defenders alone never win the game — you must bid and make a contract.",
            )
            RuleSection(
                "Players, teams and decks",
                "• 4 players (standard): two teams of two, partners opposite. 43-card deck — " +
                    "A to 4 in the red suits, A to 5 in the black suits, plus one Joker.\n" +
                    "• 6 players: 63-card deck — the full 52 plus 11s and 12s of every suit, " +
                    "the red 13s, and the Joker. The 11/12/13 rank above the 10 and below the " +
                    "Jack. Two team structures are offered: two teams of three (alternating " +
                    "seats), or three teams of two with partners seated opposite. With three " +
                    "teams each defending team scores its own tricks, only one teammate sits " +
                    "out on a misère, and a team falling to −500 ends the game with the " +
                    "best-scoring other team the winner.\n" +
                    "• 2 players: no partners. The 43-card deck is used; after both hands and " +
                    "the kitty are dealt the remaining 20 cards are out of play, face down.",
            )
            RuleSection(
                "The deal",
                "Each player receives 10 cards, dealt in packets of 3, 4, then 3. One card is " +
                    "dealt to the kitty after each packet round, making a 3-card kitty. The " +
                    "deal rotates clockwise between hands; the white D marker on the table shows " +
                    "who dealt. If every player passes the auction, the hand is thrown in and the " +
                    "next dealer deals.",
            )
            RuleSection(
                "Bidding",
                "Bidding starts left of the dealer and proceeds clockwise. A bid names a " +
                    "number of tricks (6–10) and a trump suit or no trumps; each bid must " +
                    "outrank the previous one. Once you pass you are out of the auction. The " +
                    "auction ends when only the highest bidder remains.\n\n" +
                    "Bids rank by point value (the Avondale schedule):",
            )
            BidTable()
            RuleSection(
                "",
                "Misère (win no tricks, played at no trumps, worth 250) ranks between 8♠ and " +
                    "8♣, but may only be called once the bidding has reached seven tricks — " +
                    "a bid of 7♠ or higher. Open Misère is the highest-RANKING bid — nothing " +
                    "can outbid it — although at 500 points it is worth less than 10NT's 520; " +
                    "it is gated the same way, and the declarer's hand is exposed to the other " +
                    "players once play begins.\n\n" +
                    "On a Misère the declarer plays alone: their partner (both teammates at " +
                    "6 players) sits the hand out.",
            )
            RuleSection(
                "The kitty",
                "The auction winner (the declarer) takes the 3 kitty cards into hand and " +
                    "discards any 3 cards face down. The discards are out of play and score " +
                    "for nobody.",
            )
            RuleSection(
                "Play",
                "The declarer leads the first trick; the winner of each trick leads the next. " +
                    "You must follow the suit that was led if you hold one; otherwise you may " +
                    "play anything, including a trump. The highest trump wins the trick, or " +
                    "the highest card of the led suit if no trump was played.",
            )
            RuleSection(
                "Trumps, bowers and the Joker",
                "In a suit contract the trump order, highest first, is:\n" +
                    "Joker · right bower (Jack of trumps) · left bower (Jack of the other " +
                    "same-colour suit) · A · K · Q · (13 · 12 · 11) · 10 …\n\n" +
                    "The left bower counts as a trump, not as its printed suit — it must " +
                    "follow trumps, and cannot be played to follow its printed suit.\n\n" +
                    "At no trumps the Joker is the only trump and wins any trick it is played " +
                    "to. When the Joker is led at no trumps the other players may play " +
                    "anything.",
            )
            RuleSection(
                "Scoring",
                "If the declarer's side takes at least the bid number of tricks it scores the " +
                    "contract's value; otherwise it loses that value. Taking all 10 tricks is " +
                    "a SLAM, worth a minimum of 250 — so a slam on a cheap contract (like 6♠, " +
                    "normally 40) still pays 250. The defenders score 10 points per trick " +
                    "they take, made or not — except against a Misère, where the defenders " +
                    "score nothing.\n\n" +
                    "A Misère scores only if the declarer takes no tricks at all.",
            )
            RuleSection(
                "Winning and losing",
                "The game ends when the declaring side makes a contract that takes it to " +
                    "+500 or beyond — that side wins. If either side's score reaches −500, " +
                    "that side loses immediately and the other side wins.",
            )
            RuleSection(
                "House rules",
                "The ⚙ settings menu can disable Misère bids and/or no-trump bids for new " +
                    "games. Disabling no trumps does not affect Misère, which is still played " +
                    "without trumps.",
            )
        }
        HorizontalDivider(color = ReaderTint, thickness = 2.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            ReaderTextButton("Close", onClick = onDismiss, emphasized = true, modifier = Modifier.testTag("rulesClose"))
        }
    }
}

@Composable
private fun RuleSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (title.isNotEmpty()) {
            Text(
                title,
                color = InkOnCardSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        SuitText(body, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

/** The Avondale bid values, generated from the engine's actual schedule so it can never drift. */
@Composable
private fun BidTable() {
    val schedule = ScoreSchedule.Avondale
    val trumps = listOf(Trump.SPADES, Trump.CLUBS, Trump.DIAMONDS, Trump.HEARTS, Trump.NO_TRUMP)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(ReaderTint, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            TableCell("")
            trumps.forEach { TableCell(it.symbol, bold = true) }
        }
        for (level in 6..10) {
            Row(Modifier.fillMaxWidth()) {
                TableCell("$level", bold = true)
                trumps.forEach { trump ->
                    TableCell("${schedule.value(Bid.Named(level, trump))}")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            TableCell("Misère ${schedule.value(Bid.Misere)} · Open Misère ${schedule.value(Bid.OpenMisere)}", span = 6)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(text: String, bold: Boolean = false, span: Int = 1) {
    SuitText(
        text,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.weight(span.toFloat()),
    )
}
