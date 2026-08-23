// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.ui.AppDistribution
import io.github.rotundtapir.cardkit.ui.AppPlatform
import io.github.rotundtapir.fivehundred.online.compareVersions
import io.github.rotundtapir.fivehundred.online.updateGuidance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateGuidanceTest {

    @Test
    fun `outdated android foss build is pointed at f-droid with both versions named`() {
        val g = updateGuidance("0.4.2", "0.5.0", AppPlatform.ANDROID, AppDistribution.FOSS)
        assertTrue("0.4.2" in g.body && "0.5.0" in g.body, g.body)
        assertTrue("too old" in g.body, g.body)
        assertEquals("Open F-Droid", g.actionLabel)
        assertEquals(ProjectLinks.FDROID_LISTING, g.actionUrl)
        assertFalse(g.reload)
    }

    @Test
    fun `outdated android play build is pointed at the play store`() {
        val g = updateGuidance("0.4.2", "0.5.0", AppPlatform.ANDROID, AppDistribution.PLAY)
        assertEquals("Open Play Store", g.actionLabel)
        assertEquals(ProjectLinks.PLAY_LISTING, g.actionUrl)
    }

    @Test
    fun `web gets a reload action, not a store link`() {
        val g = updateGuidance("0.4.2", "0.5.0", AppPlatform.WEB, AppDistribution.WEB)
        assertEquals("Reload", g.actionLabel)
        assertNull(g.actionUrl)
        assertTrue(g.reload)
        assertTrue("Reload" in g.body, g.body)
    }

    @Test
    fun `a client NEWER than the floor is not told it is too old`() {
        // Protocol mismatch trips the same server gate; the copy must not blame the client's age.
        val g = updateGuidance("0.6.0", "0.5.0", AppPlatform.ANDROID, AppDistribution.FOSS)
        assertFalse("too old" in g.body, g.body)
        assertTrue("server" in g.body, g.body)
        // The update route is still offered — an update may genuinely exist.
        assertEquals("Open F-Droid", g.actionLabel)
    }

    @Test
    fun `sideloaded android build gets no action button`() {
        val g = updateGuidance("0.4.2", "0.5.0", AppPlatform.ANDROID, AppDistribution.UNKNOWN)
        assertNull(g.actionLabel)
        assertNull(g.actionUrl)
    }

    @Test
    fun `version comparison is numeric per segment and tolerant of suffixes`() {
        assertTrue(compareVersions("0.4.2", "0.5.0") < 0)
        assertTrue(compareVersions("0.10.0", "0.9.9") > 0) // numeric, not lexicographic
        assertEquals(0, compareVersions("0.5", "0.5.0"))
        assertEquals(0, compareVersions("0.5.0-dev", "0.5.0"))
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
    }
}
