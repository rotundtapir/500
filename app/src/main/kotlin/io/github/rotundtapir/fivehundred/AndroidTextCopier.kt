// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService

/** Puts text on the Android clipboard — the About dialog's "Copy details" button. */
class AndroidTextCopier(private val context: Context) : TextCopier {
    override fun copy(text: String): Boolean {
        val clipboard = context.getSystemService<ClipboardManager>() ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("500 build details", text))
        return true
    }
}
