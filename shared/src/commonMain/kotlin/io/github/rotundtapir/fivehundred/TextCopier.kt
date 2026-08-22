// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

/**
 * Platform hook for putting text on the clipboard — the About dialog's "Copy" button, so a bug
 * report can carry the build details verbatim. Supplied by each entry point (like [LinkSharer]);
 * the default is a no-op, and a false return tells the dialog to point at the (selectable) details
 * on screen instead of claiming a copy that never happened.
 */
fun interface TextCopier {
    /** Copies [text] to the clipboard. Returns false where no clipboard is available. */
    fun copy(text: String): Boolean

    companion object {
        /** No clipboard: previews, tests, and any entry point that hasn't wired one up. */
        val None: TextCopier = TextCopier { false }
    }
}
