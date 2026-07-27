// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.ui.AcknowledgmentsDialog
import io.github.rotundtapir.cardkit.ui.LocalAppConfig
import io.github.rotundtapir.cardkit.ui.settings.BotSkill
import io.github.rotundtapir.cardkit.ui.settings.CycleButtonRow
import io.github.rotundtapir.cardkit.ui.settings.SectionHeader
import io.github.rotundtapir.cardkit.ui.settings.SliderRow
import io.github.rotundtapir.cardkit.ui.settings.SupportSection
import io.github.rotundtapir.cardkit.ui.settings.SwitchRow
import io.github.rotundtapir.fivehundred.SettingsDefaults

/**
 * The settings dialog, opened from the cog on the home screen or in a game, composed from
 * cardkit-ui's setting-row primitives (the labels, order and testTags are unchanged). With
 * [inGame] set, the house-rule switches (which only apply to new games) are disabled.
 */
@Composable
fun SettingsDialog(
    settings: SettingsControls,
    inGame: Boolean,
    monetization: Monetization,
    onDismiss: () -> Unit,
) {
    var showAcknowledgments by remember { mutableStateOf(false) }
    if (showAcknowledgments) {
        AcknowledgmentsDialog(onDismiss = { showAcknowledgments = false })
        return
    }
    var showRules by remember { mutableStateOf(false) }
    if (showRules) {
        RulesDialog(onDismiss = { showRules = false })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            // The list can exceed the dialog's height (small screens, the Online section), so make
            // the body scrollable instead of clipping the lower controls.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                CycleButtonRow(
                    label = "Animations",
                    value = settings.animationSpeed.label,
                    onClick = settings.onCycleAnimationSpeed,
                    buttonModifier = Modifier.testTag("animationSpeed"),
                )
                SwitchRow(
                    label = "Sort hand by default",
                    checked = settings.sortByDefault,
                    onCheckedChange = settings.onSetSortByDefault,
                    switchModifier = Modifier.testTag("sortDefault"),
                )
                SwitchRow(
                    label = "Hold completed tricks",
                    checked = settings.holdTricks,
                    onCheckedChange = settings.onSetHoldTricks,
                    switchModifier = Modifier.testTag("holdTricks"),
                )
                SliderRow(
                    label = "Sound volume",
                    value = settings.soundVolume,
                    onValueChange = settings.onSetSoundVolume,
                    sliderModifier = Modifier.testTag("volumeSlider"),
                )

                HorizontalDivider()

                SectionHeader("House rules (apply to new games)")
                // In-game these can't take effect until the next game — shown but disabled.
                val houseRuleColor =
                    if (inGame) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else Color.Unspecified
                SwitchRow(
                    label = "Misère bids",
                    checked = settings.misereEnabled,
                    onCheckedChange = settings.onSetMisereEnabled,
                    enabled = !inGame,
                    labelColor = houseRuleColor,
                    switchModifier = Modifier.testTag("misereEnabled"),
                )
                SwitchRow(
                    label = "No-trump bids",
                    checked = settings.noTrumpsEnabled,
                    onCheckedChange = settings.onSetNoTrumpsEnabled,
                    enabled = !inGame,
                    labelColor = houseRuleColor,
                    switchModifier = Modifier.testTag("noTrumpsEnabled"),
                )

                HorizontalDivider()

                SectionHeader("Bot opponents (apply to new games)")
                SwitchRow(
                    label = "Advanced AI",
                    checked = settings.botSkill == BotSkill.ADVANCED,
                    onCheckedChange = {
                        settings.onSetBotSkill(if (it) BotSkill.ADVANCED else BotSkill.STANDARD)
                    },
                    enabled = !inGame,
                    labelColor = houseRuleColor,
                    switchModifier = Modifier.testTag("advancedAi"),
                )
                Text(
                    "Bots think for up to a few seconds per move. Stronger play. Local games only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                SectionHeader("Online")
                OutlinedTextField(
                    value = settings.serverUrl,
                    onValueChange = settings.onSetServerUrl,
                    label = { Text("Game server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("serverUrl"),
                )
                if (settings.serverUrl != SettingsDefaults.SERVER_URL) {
                    TextButton(
                        onClick = { settings.onSetServerUrl(SettingsDefaults.SERVER_URL) },
                        modifier = Modifier.testTag("serverUrlReset"),
                    ) { Text("Reset to official server") }
                }

                HorizontalDivider()

                val adsRemoved by monetization.adsRemoved.collectAsState()
                val privacyOptionsRequired by monetization.privacyOptionsRequired.collectAsState()
                val uriHandler = LocalUriHandler.current
                val feedbackUri = LocalAppConfig.current.feedbackUri
                SupportSection(
                    offersRemoveAds = monetization.offersRemoveAds,
                    adsRemoved = adsRemoved,
                    onRemoveAdsOrDonate = { monetization.launchRemoveAdsOrDonate() },
                    privacyOptionsRequired = privacyOptionsRequired,
                    onShowPrivacyOptions = { monetization.showPrivacyOptionsForm() },
                    onFeedback = {
                        // FOSS/web: the GitHub issue tracker; Play: a mailto to the developer.
                        runCatching { uriHandler.openUri(feedbackUri) }
                    },
                    onAcknowledgments = { showAcknowledgments = true },
                ) {
                    OutlinedButton(
                        onClick = { showRules = true },
                        modifier = Modifier.fillMaxWidth().testTag("helpButton"),
                    ) { Text("Help — rules of 500") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
