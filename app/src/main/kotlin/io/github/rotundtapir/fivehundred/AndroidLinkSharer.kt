// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Context
import android.content.Intent

/** Shares an invite link through Android's native share sheet (`ACTION_SEND`). */
class AndroidLinkSharer(private val context: Context) : LinkSharer {
    override fun share(message: String, url: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$message\n$url")
            putExtra(Intent.EXTRA_TITLE, message)
        }
        // FLAG_ACTIVITY_NEW_TASK so this works even when handed an application context.
        context.startActivity(Intent.createChooser(send, message).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false // handed to the share sheet, which shows its own confirmation UI
    }
}
