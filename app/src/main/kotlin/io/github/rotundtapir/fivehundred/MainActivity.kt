// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.ui.theme.CardkitTheme
import io.github.rotundtapir.fivehundred.ui.GameScreen
import io.github.rotundtapir.fivehundred.ui.HomeScreen

class MainActivity : ComponentActivity() {
    companion object {
        /** Intent extra overriding the game seed — set by instrumentation tests for reproducibility. */
        const val EXTRA_SEED = "io.github.rotundtapir.fivehundred.SEED"
    }

    private lateinit var monetization: Monetization

    private fun newGameSeed(): Long =
        if (intent?.hasExtra(EXTRA_SEED) == true) intent.getLongExtra(EXTRA_SEED, 0)
        else System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monetization = MonetizationProvider.create(this)
        setContent {
            CardkitTheme {
                FiveHundredApp(monetization, this, ::newGameSeed)
            }
        }
    }

    override fun onDestroy() {
        monetization.dispose()
        super.onDestroy()
    }
}

@Composable
private fun FiveHundredApp(monetization: Monetization, activity: Activity, nextSeed: () -> Long) {
    val vm: GameViewModel = viewModel()
    var inGame by remember { mutableStateOf(false) }
    val view by vm.humanView.collectAsState()

    if (!inGame) {
        HomeScreen(
            monetization = monetization,
            activity = activity,
            onNewGame = {
                vm.newGame(nextSeed())
                inGame = true
            },
        )
    } else {
        val current = view
        if (current == null) {
            HomeScreen(monetization, activity, onNewGame = { vm.newGame(nextSeed()) })
        } else {
            GameScreen(
                view = current,
                monetization = monetization,
                activity = activity,
                onBid = vm::placeBid,
                onDiscard = vm::discard,
                onPlay = { card -> vm.playCard(card) },
                onExit = { inGame = false },
            )
        }
    }
}
