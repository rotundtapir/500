// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.web

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.window.ComposeViewport
import io.github.rotundtapir.cardkit.monetization.browser.BrowserMonetization
import io.github.rotundtapir.cardkit.ui.CardArtWarmup
import io.github.rotundtapir.cardkit.ui.theme.CardkitTheme
import io.github.rotundtapir.fivehundred.AnimationSpeed
import io.github.rotundtapir.fivehundred.AppConfig
import io.github.rotundtapir.fivehundred.FiveHundredApp
import io.github.rotundtapir.fivehundred.web.generated.resources.Res
import io.github.rotundtapir.fivehundred.web.generated.resources.symbol_fallback
import kotlin.random.Random
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.configureWebResources
import org.jetbrains.compose.resources.preloadFont
import org.w3c.dom.url.URLSearchParams

// Kept in sync with the Android FOSS flavor (MonetizationProvider / .github/FUNDING.yml).
private const val DONATION_URL = "https://liberapay.com/rotund-tapir"
private const val FEEDBACK_URI = "https://github.com/rotundtapir/500/issues"

/**
 * Browser entry point. URL query parameters mirror MainActivity's test-override intent extras:
 * `?seed=42&animationSpeed=OFF&soundVolume=0` reproduces the instrumentation fixture.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class)
fun main() {
    // GitHub Pages serves the app from a repository subpath (…github.io/500/), so resource
    // requests must stay relative instead of assuming the site root.
    configureWebResources {
        resourcePathMapping { path -> "./$path" }
    }

    val params = URLSearchParams(window.location.search.toJsString())
    val seedOverride = params.get("seed")?.toLongOrNull()
    val animationSpeedOverride = params.get("animationSpeed")
        ?.let { name -> AnimationSpeed.entries.find { it.name == name } }
    val soundVolumeOverride = params.get("soundVolume")?.toFloatOrNull()

    ComposeViewport(document.body!!) {
        // The embedded default font lacks the symbols the UI draws (card suits, arrows, the
        // settings gear, check marks) and the web canvas has no system fonts to fall back on, so
        // register a subset of DejaVu Sans (see web/FONT_LICENSE-DejaVu.txt) covering those
        // blocks as a fallback before showing any text.
        val suitFont by preloadFont(Res.font.symbol_fallback)
        val resolver = LocalFontFamilyResolver.current
        var fontsReady by remember { mutableStateOf(false) }
        LaunchedEffect(suitFont) {
            suitFont?.let {
                resolver.preload(FontFamily(it))
                fontsReady = true
            }
        }
        // If the font never arrives (network failure), show the app anyway after a grace period —
        // missing suit glyphs beat a permanently blank page.
        LaunchedEffect(Unit) {
            delay(5_000)
            fontsReady = true
        }
        if (!fontsReady) return@ComposeViewport
        // First real frame is about to compose — only now retire the static "loading" placeholder.
        LaunchedEffect(Unit) {
            document.getElementById("loading")?.remove()
        }
        CardkitTheme {
            Box {
                // Web image loading is async: warm every card bitmap into the resource cache at
                // startup so the first deal doesn't show blank backs/faces while PNGs stream in.
                CardArtWarmup()
                FiveHundredApp(
                    monetization = remember { BrowserMonetization(DONATION_URL) },
                    settings = remember { LocalStorageSettingsRepository() },
                    appConfig = AppConfig(feedbackUri = FEEDBACK_URI),
                    nextSeed = { seedOverride ?: Random.nextLong() },
                    animationSpeedOverride = animationSpeedOverride,
                    soundVolumeOverride = soundVolumeOverride,
                )
            }
        }
    }
}
