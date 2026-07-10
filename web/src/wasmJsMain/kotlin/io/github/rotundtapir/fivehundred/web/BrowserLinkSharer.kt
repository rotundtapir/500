// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.web

import io.github.rotundtapir.fivehundred.LinkSharer

/**
 * Web share: there is no reliable native share sheet across desktop browsers, so copy the invite
 * link to the clipboard (the UI shows a brief "copied" confirmation). The Clipboard API needs a
 * secure context, which the HTTPS Pages site (and localhost) provide.
 */
class BrowserLinkSharer : LinkSharer {
    override fun share(message: String, url: String): Boolean {
        writeClipboard(url)
        return true
    }
}

// `&&` short-circuits so this is a no-op (rather than throwing) where the Clipboard API is absent.
// (detekt can't see that the js() body uses `text`, hence the suppression.)
@Suppress("UnusedParameter")
private fun writeClipboard(text: String): Unit =
    js("navigator.clipboard && navigator.clipboard.writeText(text)")
