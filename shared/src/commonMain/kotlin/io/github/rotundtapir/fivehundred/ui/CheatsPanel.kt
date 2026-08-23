// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.ui.settings.SectionHeader
import io.github.rotundtapir.cardkit.ui.settings.SwitchRow

/**
 * The hidden cheats section of the settings dialog: developer-style toys, unlocked by tapping the
 * About dialog's version row (see [AboutDialog]) and persisted through `SettingsRepository`.
 *
 * **Offline only, and structurally so where possible.** The caller must not render this during an
 * online game — the server is authoritative and redacts per seat, so these cannot work there — but
 * the important guarantee is stronger than that check: "see all hands" reads the local
 * `GameViewModel.allHands`, which online is simply empty, and the seed likewise only exists on the
 * local ViewModel. Nothing here widens `PlayerView`, so nothing here can leak over the wire.
 *
 * The seed deserves its own note: the engine is public and strictly seed-deterministic, so a seed
 * is equivalent to the whole game state (all four hands, every future deal). Showing it is a real
 * exploit in an online game and merely a convenience in a local one.
 */
@Immutable
data class CheatControls(
    /** The current local match's seed, or null before a game starts. Never non-null online. */
    val seed: Long?,
    /** Show every seat's cards on the felt. */
    val showAllHands: Boolean,
    val onSetShowAllHands: (Boolean) -> Unit,
    /** Deal again — a fresh match on [seed]'s successor, or on a seed the user supplies. */
    val onRedeal: (Long?) -> Unit,
    /**
     * Whether the game screen shows its own re-deal button. Cycling through deals is the point of
     * the cheat, and a round trip through this dialog per deal defeats it.
     */
    val redealOnFelt: Boolean,
    val onSetRedealOnFelt: (Boolean) -> Unit,
    /** Search for a seed that deals the local player a hand worth the given bid level. */
    val onRiggedDeal: (level: Int) -> Unit,
    /** Progress/outcome text from the last rigged-deal search, or null when idle. */
    val searchStatus: String?,
    /** Put text (the seed) on the clipboard; false when there is no clipboard to take it. */
    val onCopy: (String) -> Boolean,
    /** Re-lock the menu — an unlock the user cannot undo is a trap. */
    val onRelock: () -> Unit,
)

@Composable
fun CheatsSection(cheats: CheatControls, modifier: Modifier = Modifier) {
    var seedInput by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf<Boolean?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.testTag("cheatsSection")) {
        SectionHeader("Cheats (local games only)")

        // The seed IS the game state, so this doubles as the reproduction half of a bug report:
        // "the deal was bizarre" becomes a seed anyone can replay.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                cheats.seed?.let { "Seed: $it" } ?: "Seed: — (no game in progress)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("cheatSeed"),
            )
        }
        cheats.seed?.let { seed ->
            OutlinedButton(
                onClick = { copied = cheats.onCopy(seed.toString()) },
                modifier = Modifier.fillMaxWidth().testTag("cheatCopySeed"),
            ) { Text("Copy seed") }
            copied?.let { ok ->
                Text(
                    if (ok) "Copied." else "Couldn't copy — the seed is shown above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SwitchRow(
            label = "Reveal hands and the kitty",
            checked = cheats.showAllHands,
            onCheckedChange = cheats.onSetShowAllHands,
            switchModifier = Modifier.testTag("cheatShowAllHands"),
        )
        Text(
            "Tap a player's cards to see that hand — one at a time, so the felt keeps its room. " +
                "The kitty turns face up too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = { cheats.onRedeal(null) },
            modifier = Modifier.fillMaxWidth().testTag("cheatRedeal"),
        ) { Text("Re-deal (new match, same table)") }

        SwitchRow(
            label = "Re-deal button on the felt",
            checked = cheats.redealOnFelt,
            onCheckedChange = cheats.onSetRedealOnFelt,
            switchModifier = Modifier.testTag("cheatRedealOnFelt"),
        )
        Text(
            "Adds a ⟳ button under the felt, so you can cycle deals without reopening settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = seedInput,
            onValueChange = { typed -> seedInput = typed.filter { it.isDigit() || it == '-' } },
            label = { Text("Play a specific seed") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("cheatSeedInput"),
        )
        OutlinedButton(
            onClick = { seedInput.toLongOrNull()?.let(cheats.onRedeal) },
            enabled = seedInput.toLongOrNull() != null,
            modifier = Modifier.fillMaxWidth().testTag("cheatPlaySeed"),
        ) { Text("Deal that seed") }

        Text(
            "Rigged deck — search for a seed that deals you a hand worth this bid:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (level in RIGGED_LEVELS) {
                OutlinedButton(
                    onClick = { cheats.onRiggedDeal(level) },
                    modifier = Modifier.weight(1f).testTag("cheatRig$level"),
                ) { Text("$level+") }
            }
        }
        cheats.searchStatus?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("cheatSearchStatus"),
            )
        }

        TextButton(
            onClick = cheats.onRelock,
            modifier = Modifier.fillMaxWidth().testTag("cheatRelock"),
        ) { Text("Lock cheats again") }
    }
}

/** Bid levels the rigged-deck search offers. 8+ is already uncommon; 9+/10+ take longer to find. */
private val RIGGED_LEVELS = listOf(7, 8, 9)
