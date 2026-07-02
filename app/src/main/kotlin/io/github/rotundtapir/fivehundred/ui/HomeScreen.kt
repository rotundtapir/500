// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.fivehundred.AnimationSpeed

@Composable
fun HomeScreen(
    monetization: Monetization,
    activity: Activity,
    onNewGame: () -> Unit,
    animationSpeed: AnimationSpeed,
    onCycleAnimationSpeed: () -> Unit,
    sortByDefault: Boolean,
    onSetSortByDefault: (Boolean) -> Unit,
    playerCount: Int,
    onPlayerCountChange: (Int) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .testTag("settingsButton"),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("500", fontSize = 72.sp, fontWeight = FontWeight.Bold)
                Text("Australian rules · you vs the bots", fontSize = 16.sp)
                Spacer(Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (count in intArrayOf(2, 4, 6)) {
                        PlayerCountButton(
                            count = count,
                            selected = count == playerCount,
                            onClick = { onPlayerCountChange(count) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onNewGame,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFAFAFA),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("New Game", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(16.dp))

                val adsRemoved by monetization.adsRemoved.collectAsState()
                OutlinedButton(
                    onClick = { monetization.launchRemoveAdsOrDonate(activity) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)),
                ) {
                    Text(
                        when {
                            !monetization.offersRemoveAds -> "Support development"
                            adsRemoved -> "Ads removed — thank you!"
                            else -> "Remove ads"
                        }
                    )
                }

                Spacer(Modifier.height(24.dp))
                monetization.BannerSlot(Modifier.fillMaxWidth())
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            animationSpeed = animationSpeed,
            onCycleAnimationSpeed = onCycleAnimationSpeed,
            sortByDefault = sortByDefault,
            onSetSortByDefault = onSetSortByDefault,
            onDismiss = { showSettings = false },
        )
    }
}

/** One option in the 2/4/6 player-count selector; the selected one gets filled emphasis. */
@Composable
private fun PlayerCountButton(
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    OutlinedButton(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = onBackground.copy(alpha = 0.15f),
                contentColor = onBackground,
            )
        } else {
            ButtonDefaults.outlinedButtonColors(contentColor = onBackground.copy(alpha = 0.7f))
        },
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            onBackground.copy(alpha = if (selected) 1f else 0.4f),
        ),
        modifier = Modifier.testTag("players:$count"),
    ) {
        Text(
            "$count players",
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
