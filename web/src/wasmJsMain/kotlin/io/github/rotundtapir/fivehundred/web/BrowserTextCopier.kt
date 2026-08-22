// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.web

import io.github.rotundtapir.fivehundred.TextCopier

/**
 * Copies text with the browser's Clipboard API — the About dialog's "Copy details" button. The API
 * needs a secure context (https / localhost) and a user gesture; the button press is that gesture.
 */
class BrowserTextCopier : TextCopier {
    override fun copy(text: String): Boolean = writeClipboardText(text)
}

// `navigator.clipboard` is absent on insecure origins and older browsers — report that back as
// false rather than throwing, so the dialog can tell the user to select the lines instead.
// (detekt can't see that the js() body uses `text`, hence the suppression.)
@Suppress("UnusedParameter")
private fun writeClipboardText(text: String): Boolean =
    js("!!(navigator.clipboard && navigator.clipboard.writeText(text))")
