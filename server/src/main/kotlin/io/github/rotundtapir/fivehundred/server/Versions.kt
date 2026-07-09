// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

/** Dotted-numeric version comparison ("0.3.0" style). Non-numeric/junk segments sort as 0. */
object Versions {
    private fun parts(v: String): List<Int> =
        v.trim().split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** Standard comparison: negative if [a] < [b], 0 if equal, positive if [a] > [b]. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** True iff [appVersion] is at least [minimum]. */
    fun isAtLeast(appVersion: String, minimum: String): Boolean = compare(appVersion, minimum) >= 0
}
