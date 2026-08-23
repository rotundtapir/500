// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Platform hook for restarting the app in place. Only the web entry point provides a real one
 * (`window.location.reload()` — a stale wasm client is fixed by a reload, not a store visit);
 * Android builds keep the no-op default and offer a store link instead (see the "Update required"
 * dialog in `ui/online/OnlineFlow.kt`). Same seam pattern as [LinkSharer]/[LocalLinkSharer].
 */
fun interface AppReloader {
    fun reload()
}

/** Provided by [FiveHundredApp]; defaults to a no-op so previews/tests need no wiring. */
val LocalAppReloader = staticCompositionLocalOf { AppReloader { } }
