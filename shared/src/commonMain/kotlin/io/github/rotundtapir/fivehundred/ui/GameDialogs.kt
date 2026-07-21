// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.engine.HandResult
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.ScoreSchedule
import io.github.rotundtapir.fivehundred.engine.label
import io.github.rotundtapir.fivehundred.engine.teamOf

private fun signed(delta: Int): String = if (delta < 0) "−${-delta}" else "+$delta"

/**
 * Whether the hand's contract outcome favoured [myTeam]: true when my side made its contract or an
 * opposing side failed theirs. This — not the sign of my team's point delta — drives the
 * hand-result header tint: defenders usually pick up some trick points even when the opponents
 * make their bid, and a green banner over "Frank made 6♦!" read as good news.
 */
internal fun handWentMyWay(result: HandResult, myTeam: Int, teamCount: Int): Boolean =
    result.made == (teamOf(result.contract.declarer, teamCount) == myTeam)

@Composable
internal fun HandResultDialog(
    view: PlayerView,
    botNames: Map<Seat, String>,
    onDismiss: () -> Unit = {},
) {
    val result = view.lastHandResult ?: return
    // Keyed on the scored-hand COUNT, not the HandResult value: two consecutive hands can score
    // structurally identically (same declarer, bid and tricks), and a value key would then never
    // reset — the second dialog would never show and the acknowledgement gates would deadlock.
    var dismissed by remember(view.handResults.size) { mutableStateOf(false) }
    if (dismissed) return
    val dismiss = {
        dismissed = true
        onDismiss()
    }

    val contract = result.contract
    val declarerTeam = teamOf(contract.declarer, view.teamCount)
    val myTeam = view.myTeam
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
        // Taking all 10 tricks is a slam, scored at max(contract value, 250) — call out the floor
        // when it actually lifted the award above the contract's face value (e.g. 7♦'s 180 → 250).
        val slamFloorApplied = result.made && result.declarerTricks == 10 &&
            (result.teamDeltas[declarerTeam] ?: 0) > ScoreSchedule.Avondale.value(contract.bid)
        return when {
            team == declarerTeam && slamFloorApplied -> "slam — all 10 tricks scores at least 250"
            team == declarerTeam -> if (result.made) "contract made" else "contract failed"
            contract.isMisere -> "defenders don't score"
            else -> "$teamTricks ${if (teamTricks == 1) "trick" else "tricks"} × 10"
        }
    }

    // The title names the declarer ("Alice made 8♦!") so the wording can't contradict the tint.
    val wentOurWay = handWentMyWay(result, myTeam, view.teamCount)
    val headerColor = if (wentOurWay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val onHeaderColor = if (wentOurWay) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError
    val declarerName = seatLabel(view, botNames, contract.declarer)
    val title = if (result.made) {
        "$declarerName made ${contract.bid.label}!"
    } else {
        "$declarerName failed ${contract.bid.label}"
    }

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
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = onHeaderColor,
                        textAlign = TextAlign.Center,
                    )
                }
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SuitText(bidLine, style = MaterialTheme.typography.bodyMedium)
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

/**
 * End-of-game dialog: a win/lose banner, the final totals, and a hand-by-hand score sheet built
 * from [PlayerView.handResults]. Shown only after the final hand's [HandResultDialog] has been
 * dismissed, so the last hand's breakdown is never skipped.
 */
@Composable
internal fun GameOverDialog(
    view: PlayerView,
    botNames: Map<Seat, String>,
    onBackToMenu: () -> Unit,
) {
    val youWon = view.winner == view.myTeam
    val headerColor = if (youWon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val onHeaderColor = if (youWon) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError
    // Our team first, then every other team in index order (just "Them" with two teams).
    val teamsInOrder = listOf(view.myTeam) + (0 until view.teamCount).filter { it != view.myTeam }

    fun columnLabel(team: Int): String = when {
        team == view.myTeam -> "Us"
        view.teamCount == 2 -> "Them"
        else -> teamLabel(view, botNames, team)
    }

    val handCount = view.handResults.size
    val teamCellWidth = if (view.teamCount == 2) 64.dp else 56.dp

    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
        ) {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerColor)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (youWon) "You win!" else "You lose",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = onHeaderColor,
                    )
                    Text(
                        "after $handCount ${if (handCount == 1) "hand" else "hands"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = onHeaderColor,
                    )
                }
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    // Final totals, the winning team's tinted to match the banner. Paired team
                    // names stack onto two lines rather than ellipsizing in their third of the row.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        teamsInOrder.forEach { team ->
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    columnLabel(team).replace(" & ", " &\n"),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    "${view.scores[team] ?: 0}",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (team == view.winner) headerColor else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    // Score sheet header: contract column, then one delta column per team. Paired
                    // team names ("Wally & Olive") don't fit a delta column on one line, so stack
                    // them ("Wally &" over "Olive") instead of ellipsizing into "Wally & …".
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            "Hand",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        teamsInOrder.forEach { team ->
                            Text(
                                columnLabel(team).replace(" & ", " &\n"),
                                style = if (view.teamCount == 2) {
                                    MaterialTheme.typography.labelMedium
                                } else {
                                    MaterialTheme.typography.labelSmall
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier
                                    .width(teamCellWidth)
                                    .padding(start = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        view.handResults.forEachIndexed { i, r ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${i + 1}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(24.dp),
                                )
                                SuitText(
                                    "${r.contract.bid.label} · ${seatLabel(view, botNames, r.contract.declarer)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    // Long contracts ("Misère · Thelma") wrap rather than losing
                                    // the declarer to an ellipsis in the narrow three-team layout.
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (r.made) "✓" else "✗",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (r.made) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                teamsInOrder.forEach { team ->
                                    Text(
                                        signed(r.teamDeltas[team] ?: 0),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (team == view.myTeam) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.width(teamCellWidth),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onBackToMenu,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("backToMenu"),
                    ) { Text("Back to menu") }
                }
            }
        }
    }
}
