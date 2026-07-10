// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.online

/**
 * Invite links for online games. A link points at the public web app with the join code in a query
 * parameter, e.g. `https://rotundtapir.github.io/500/?joinCode=12AB`. Opening it joins that game —
 * in the browser directly, or in the installed Android app via App Links (see docs/deep-linking.md).
 * The web app is the canonical target because it works with no install and is what App Links verify.
 */
object JoinLink {
    /** The query parameter carrying the 4-char join code. */
    const val PARAM = "joinCode"

    /** The public web app, where an invite link opens (must match the Android App Links host/path). */
    const val WEB_BASE_URL = "https://rotundtapir.github.io/500/"

    private const val CODE_LENGTH = 4

    /** The shareable invite link for [code] (assumed already a valid uppercase code). */
    fun forCode(code: String): String = "$WEB_BASE_URL?$PARAM=${code.trim().uppercase()}"

    /**
     * Normalise a join code read from a link/query value: trim, uppercase, keep only alphanumerics,
     * and require exactly [CODE_LENGTH] characters. Returns null for anything that can't be a code
     * (the server still validates it — this only rejects obvious junk before we try to join).
     */
    fun normalizeCode(raw: String?): String? {
        val code = raw?.trim()?.uppercase()?.filter { it.isLetterOrDigit() }
        return code?.takeIf { it.length == CODE_LENGTH }
    }
}
