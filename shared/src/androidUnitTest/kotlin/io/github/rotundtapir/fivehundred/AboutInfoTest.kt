// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.ui.AppConfig
import io.github.rotundtapir.cardkit.ui.AppDistribution
import io.github.rotundtapir.cardkit.ui.AppPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The About dialog's report block. This is what bug reports are asked to paste, so the exact
 * content is pinned here rather than left to the UI.
 */
class AboutInfoTest {

    private val androidConfig = AppConfig(
        feedbackUri = ProjectLinks.ISSUE_TRACKER,
        version = "0.5.0",
        platform = AppPlatform.ANDROID,
        flavor = AppDistribution.FOSS,
        commit = "5dec28b1",
    )

    @Test
    fun `reports version, commit, build and environment`() {
        val info = AboutInfo.from(
            androidConfig,
            BuildDetails(
                versionCode = "13",
                buildType = "release",
                environment = "Android 14 (API 34) · Google Pixel 6",
            ),
            serverUrl = "wss://500.example/ws",
        )

        assertEquals("0.5.0 (13)", info.version)
        assertEquals("5dec28b1", info.commit)
        assertEquals("android · foss · release", info.build)
        assertEquals(
            """
            Version: 0.5.0 (13)
            Commit: 5dec28b1
            Build: android · foss · release
            System: Android 14 (API 34) · Google Pixel 6
            Server: wss://500.example/ws
            """.trimIndent(),
            info.asReportText(),
        )
    }

    @Test
    fun `omits build facts the platform does not have`() {
        // Blank fields are dropped rather than shown empty, and an UNKNOWN distribution
        // contributes nothing to the build line.
        val info = AboutInfo.from(
            AppConfig(
                feedbackUri = ProjectLinks.ISSUE_TRACKER,
                version = "0.5.0",
                platform = AppPlatform.WEB,
                flavor = AppDistribution.UNKNOWN,
                commit = "5dec28b1",
            ),
            BuildDetails(versionCode = "", buildType = "", environment = ""),
            serverUrl = "wss://500.example/ws",
        )

        assertEquals("0.5.0", info.version)
        assertEquals("web", info.build)
        assertFalse(info.rows().any { (label, _) -> label == "System" }, "no System row without one")
        assertEquals(
            listOf("Version", "Commit", "Build", "Server"),
            info.rows().map { (label, _) -> label },
        )
    }

    @Test
    fun `falls back to unknown when the build could not read git or the version`() {
        // An F-Droid / source-tarball build has no git metadata: say "unknown" rather than nothing,
        // so a report never silently omits the field.
        val info = AboutInfo.from(
            androidConfig.copy(version = "", commit = ""),
            BuildDetails(),
            serverUrl = "",
        )

        assertEquals(AboutInfo.UNKNOWN, info.version)
        assertEquals(AboutInfo.UNKNOWN, info.commit)
        assertTrue(info.asReportText().startsWith("Version: unknown\nCommit: unknown"))
        assertFalse(info.rows().any { (label, _) -> label == "Server" }, "no Server row when unset")
    }
}
