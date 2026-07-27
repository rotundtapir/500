// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

// AppConfig and LocalAppConfig live in cardkit-ui (io.github.rotundtapir.cardkit.ui); each entry
// point builds one and FiveHundredApp provides it. The cardkit AppPlatform/AppDistribution values
// are mapped to the wire's net.Platform/net.Distribution at the OnlineViewModel.enter boundary.

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
