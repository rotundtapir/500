// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.ui.AppConfig
import io.github.rotundtapir.cardkit.ui.AppDistribution

/**
 * Build facts an entry point knows that cardkit's [AppConfig] does not carry: the Android
 * versionCode, the build type, and a one-line description of the runtime (Android release plus
 * device, or the browser's user agent). Every field is optional — a blank one is simply left out
 * of the About dialog and the report it copies.
 */
data class BuildDetails(
    /** The Android `versionCode` (or the web build's mirror of it); blank when unknown. */
    val versionCode: String = "",
    /** "debug" or "release"; blank where the platform has no such distinction. */
    val buildType: String = "",
    /** Where the app is running: "Android 14 (API 34) · Google Pixel 6", or a browser user agent. */
    val environment: String = "",
)

/**
 * Everything the About dialog shows, and — as [asReportText] — the block users are asked to paste
 * into a bug report. Assembled by [from] out of the build's [AppConfig], its [BuildDetails] and the
 * configured game server, and kept as plain data so the exact report text is unit-testable.
 */
data class AboutInfo(
    val version: String,
    val commit: String,
    val build: String,
    val environment: String,
    val serverUrl: String,
) {
    /** Label/value pairs in display (and report) order. Values that aren't known are dropped. */
    fun rows(): List<Pair<String, String>> = listOf(
        "Version" to version,
        "Commit" to commit,
        "Build" to build,
        "System" to environment,
        "Server" to serverUrl,
    ).filter { (_, value) -> value.isNotBlank() }

    /** The paste-into-an-issue block: one `Label: value` line per row of [rows]. */
    fun asReportText(): String = rows().joinToString("\n") { (label, value) -> "$label: $value" }

    companion object {
        /** Stands in for a value the build couldn't determine (e.g. a git-less tarball build). */
        const val UNKNOWN = "unknown"

        fun from(config: AppConfig, details: BuildDetails, serverUrl: String): AboutInfo = AboutInfo(
            // "0.5.0 (13)" — the versionCode is what F-Droid/Play users can actually read back.
            version = listOfNotNull(
                config.version.ifBlank { UNKNOWN },
                details.versionCode.takeIf { it.isNotBlank() }?.let { "($it)" },
            ).joinToString(" "),
            commit = config.commit.ifBlank { UNKNOWN },
            // "android · foss · release" — enough to tell an F-Droid APK from a Play one from web.
            build = listOf(
                config.platform.name.lowercase(),
                config.flavor.reportLabel(),
                details.buildType,
            ).filter { it.isNotBlank() }.joinToString(" · "),
            environment = details.environment,
            serverUrl = serverUrl,
        )
    }
}

/** The distribution as it appears in a report; UNKNOWN contributes nothing rather than noise. */
private fun AppDistribution.reportLabel(): String =
    if (this == AppDistribution.UNKNOWN) "" else name.lowercase()
