// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Platform hook for sharing an invite link. Android hands [url] to the native share sheet; the web
 * copies it to the clipboard. Supplied by each entry point (like [Monetization]/[SettingsRepository])
 * and read through [LocalLinkSharer].
 */
fun interface LinkSharer {
    /**
     * Share [url], with a short human-readable [message] for share targets that show one.
     * Returns true when the link was copied to the clipboard (so the caller can show a "copied"
     * confirmation), false when it was handed to a native share sheet that shows its own UI.
     */
    fun share(message: String, url: String): Boolean
}

/** Provided by [FiveHundredApp]; defaults to a no-op so previews/tests need no wiring. */
val LocalLinkSharer = staticCompositionLocalOf { LinkSharer { _, _ -> false } }
