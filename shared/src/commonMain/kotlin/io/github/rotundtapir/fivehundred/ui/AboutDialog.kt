// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.ui.LocalAppConfig
import io.github.rotundtapir.fivehundred.AboutInfo

/**
 * "About": which build of the game this is. Every value here is something a bug report needs and
 * nobody can produce from memory — the version and its versionCode, the git commit, the
 * distribution (F-Droid / Play / web), the device or browser, and the configured game server.
 *
 * The "Copy details" button puts the same lines on the clipboard as [AboutInfo.asReportText], so a
 * report can paste them verbatim; where no clipboard exists the rows are selectable instead.
 */
@Composable
fun AboutDialog(
    info: AboutInfo,
    onCopy: (String) -> Boolean,
    onDismiss: () -> Unit,
    // Tapping the version row repeatedly unlocks the hidden cheats menu, the way Android's Settings
    // → About phone → Build number unlocks developer options (#51). Null when already unlocked (or
    // where no unlock should be offered), which also stops the countdown reappearing.
    onUnlockCheats: (() -> Unit)? = null,
) {
    // null until "Copy details" is pressed, then whether the clipboard actually took it.
    var copied by remember { mutableStateOf<Boolean?>(null) }
    var versionTaps by remember { mutableIntStateOf(0) }
    val tapsLeft = UNLOCK_TAPS - versionTaps
    val uriHandler = LocalUriHandler.current
    val feedbackUri = LocalAppConfig.current.feedbackUri

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About 500") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag("aboutDialog"),
            ) {
                Text(
                    "Australian rules 500 — free software, no tracking.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Selectable so the details are still recoverable by hand where the clipboard
                // button can't work (an older browser, a locked-down WebView).
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        info.rows().forEach { (label, value) ->
                            // Only the version row counts taps, and only while an unlock is on
                            // offer — so the gesture stays undiscoverable by accident but is
                            // findable by anyone who knows the Android convention.
                            val counts = label == VERSION_LABEL && onUnlockCheats != null
                            val onTap: (() -> Unit)? = if (counts) {
                                {
                                    versionTaps++
                                    if (versionTaps >= UNLOCK_TAPS) {
                                        versionTaps = 0
                                        onUnlockCheats()
                                    }
                                }
                            } else {
                                null
                            }
                            if (counts) {
                                // Selection has to be off for THIS row: on Android a press on
                                // selectable text starts the selection handles, which swallows the
                                // taps and pops a toolbar over the dialog — the gesture became
                                // almost impossible to perform. Every other row stays selectable,
                                // which is what the no-clipboard fallback actually needs (commit,
                                // device, server); the version is in "Copy details" regardless.
                                DisableSelection { AboutRow(label, value, onTap) }
                            } else {
                                AboutRow(label, value)
                            }
                        }
                    }
                }
                // The countdown only starts once a few taps have landed, so idle curiosity doesn't
                // reveal that anything is there.
                if (onUnlockCheats != null && versionTaps >= UNLOCK_HINT_AFTER && tapsLeft > 0) {
                    Text(
                        "You are now $tapsLeft ${if (tapsLeft == 1) "tap" else "taps"} away from the cheats menu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("unlockCountdown"),
                    )
                }
                Text(
                    "Please include these details in a bug report — they say exactly which build " +
                        "you are on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { copied = onCopy(info.asReportText()) },
                    modifier = Modifier.fillMaxWidth().testTag("aboutCopy"),
                ) { Text("Copy details") }
                copied?.let { ok ->
                    Text(
                        if (ok) "Copied to clipboard." else "Couldn't copy — select the lines above instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    // FOSS/web: the GitHub issue tracker; Play: a mailto to the developer.
                    onClick = { runCatching { uriHandler.openUri(feedbackUri) } },
                    modifier = Modifier.fillMaxWidth().testTag("aboutFeedback"),
                ) { Text("Report a bug") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("aboutClose")) { Text("Close") }
        },
    )
}

/** One build fact: a fixed-width label so the values line up, and the value itself. */
@Composable
private fun AboutRow(label: String, value: String, onTap: (() -> Unit)? = null) {
    // Deliberately pointerInput rather than clickable: `clickable` adds semantics that MERGE the
    // row's children into one node, which both announces the hidden gesture to screen readers and
    // hides the version text from anything locating by text (the web e2e About spec broke on it).
    // An easter egg should be invisible to the accessibility tree, not advertised in it.
    val tapModifier = if (onTap != null) {
        // A row of bodySmall text is a ~16dp-tall target; padding it out makes seven quick taps
        // land where the user is aiming instead of between the lines.
        Modifier
            .padding(vertical = 6.dp)
            .pointerInput(onTap) { detectTapGestures { onTap() } }
    } else {
        Modifier
    }
    Row(modifier = Modifier.fillMaxWidth().then(tapModifier)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The row whose label unlocks the cheats menu — must match [AboutInfo]'s own label for it. */
private const val VERSION_LABEL = "Version"

/** Taps on the version row that unlock the cheats menu; Android's developer options use 7 too. */
private const val UNLOCK_TAPS = 7

/** Taps before the countdown appears — before this, nothing hints that a gesture exists. */
private const val UNLOCK_HINT_AFTER = 3
