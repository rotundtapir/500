// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
) {
    // null until "Copy details" is pressed, then whether the clipboard actually took it.
    var copied by remember { mutableStateOf<Boolean?>(null) }
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
                        info.rows().forEach { (label, value) -> AboutRow(label, value) }
                    }
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
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
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
