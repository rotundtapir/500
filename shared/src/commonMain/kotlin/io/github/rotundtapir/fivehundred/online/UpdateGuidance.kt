// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.online

import io.github.rotundtapir.cardkit.ui.AppDistribution
import io.github.rotundtapir.cardkit.ui.AppPlatform
import io.github.rotundtapir.fivehundred.ProjectLinks

/**
 * What the "Update required" dialog should say and offer, computed from the server's
 * [io.github.rotundtapir.cardkit.net.UpdateRequired] plus what this build knows about itself.
 * Pure so it's unit-testable; the dialog just renders it.
 */
data class UpdateGuidance(
    val body: String,
    /** Label for the update action, or null when this build has no sensible route (just "OK"). */
    val actionLabel: String?,
    /** URL the action opens, or null when [reload] is the action (web) or there is no action. */
    val actionUrl: String?,
    /** True when the action is an in-place reload (web) rather than a link. */
    val reload: Boolean = false,
)

fun updateGuidance(
    currentVersion: String,
    minAppVersion: String,
    platform: AppPlatform,
    flavor: AppDistribution,
): UpdateGuidance {
    // The server's gate is exact protocol equality plus a version floor, so landing here does not
    // always mean the client is old — a client NEWER than the server fails the protocol check too.
    // Only claim "too old" when the version comparison actually says so.
    val outdated = compareVersions(currentVersion, minAppVersion) < 0
    val body = if (outdated) {
        "This version ($currentVersion) is too old for online play. " +
            "Version $minAppVersion or newer is required."
    } else {
        "This app (version $currentVersion) and the server aren't speaking the same version. " +
            "If an update is available, install it — otherwise the server may not have been " +
            "updated yet."
    }
    return when {
        platform == AppPlatform.WEB ->
            UpdateGuidance(
                body = if (outdated) {
                    "This page is running an old version ($currentVersion); " +
                        "$minAppVersion or newer is required. Reloading fetches the latest."
                } else {
                    body + " Reloading fetches the latest web version."
                },
                actionLabel = "Reload",
                actionUrl = null,
                reload = true,
            )
        flavor == AppDistribution.PLAY ->
            UpdateGuidance(body, "Open Play Store", ProjectLinks.PLAY_LISTING)
        flavor == AppDistribution.FOSS ->
            UpdateGuidance(body, "Open F-Droid", ProjectLinks.FDROID_LISTING)
        // Sideloaded/unknown Android build: no store to point at.
        else -> UpdateGuidance(body, actionLabel = null, actionUrl = null)
    }
}

/**
 * Dotted-numeric version comparison: negative when [a] < [b]. Non-numeric segments (e.g. a
 * `-SNAPSHOT`/commit suffix) compare as 0, and missing segments are 0, so "0.5" == "0.5.0" and
 * "0.5.0-dev" == "0.5.0" — close enough for choosing dialog copy, never used for gating.
 */
internal fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val cmp = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (cmp != 0) return cmp
    }
    return 0
}
