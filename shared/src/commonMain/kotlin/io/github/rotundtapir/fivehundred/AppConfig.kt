// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.rotundtapir.fivehundred.net.Platform

/**
 * Build-specific values the shared UI needs, supplied by each entry point (they came from AGP's
 * BuildConfig when the UI lived in the Android app, which multiplatform code cannot see).
 */
data class AppConfig(
    /** Where "Submit feedback" goes: the GitHub issue tracker (FOSS/web) or a mailto (Play). */
    val feedbackUri: String,
    /** This build's user-facing version (e.g. "0.3.0"), reported to the online server on connect. */
    val version: String,
    /** Which client build this is, reported to the online server for cross-play diagnostics. */
    val platform: Platform,
)

/** Provided by [FiveHundredApp]; read where the UI needs a build-specific value. */
val LocalAppConfig = staticCompositionLocalOf<AppConfig> {
    error("LocalAppConfig not provided")
}

/**
 * The project's public URLs — the single source for every code reference. Two places cannot read
 * these and must be kept in sync by hand: `.github/FUNDING.yml` (GitHub's Sponsor button and
 * F-Droid's Donate metadata parse it) and the foss `FEEDBACK_URI` buildConfigField in
 * `app/build.gradle.kts`.
 */
object ProjectLinks {
    /** The donation page every non-Play distribution points at. */
    const val DONATION_URL = "https://liberapay.com/rotund-tapir"

    /** The public issue tracker — the feedback target for FOSS and web builds (Play uses a mailto). */
    const val ISSUE_TRACKER = "https://github.com/rotundtapir/500/issues"
}
