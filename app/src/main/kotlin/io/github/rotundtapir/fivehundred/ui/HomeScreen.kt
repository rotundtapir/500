// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.cardkit.monetization.Monetization

@Composable
fun HomeScreen(
    monetization: Monetization,
    activity: Activity,
    onNewGame: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("500", fontSize = 72.sp, fontWeight = FontWeight.Bold)
            Text("Australian rules · vs 3 bots", fontSize = 16.sp)
            Spacer(Modifier.height(48.dp))
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
