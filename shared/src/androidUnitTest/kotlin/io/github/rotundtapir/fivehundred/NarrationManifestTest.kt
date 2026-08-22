// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.fivehundred.ui.tutorialNarration
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The drift gate between the tutorial's on-screen words and its pre-generated voice clips.
 *
 * `scripts/generate-narration.sh` synthesizes one MP3 per [tutorialNarration] line and records
 * each line's SHA-256 in the manifest beside the clips (the script and the manifest header name
 * the engine). If a tutorial text changes without
 * regenerating, this test fails — the voice must always say what the screen shows.
 */
class NarrationManifestTest {

    private val narrationDir = moduleFile("src/commonMain/composeResources/files/narration")

    @Test
    fun `every narration line has a current clip and manifest entry - else regenerate`() {
        val manifestFile = File(narrationDir, "manifest.txt")
        assertTrue(
            manifestFile.exists(),
            "No narration manifest at $manifestFile — run scripts/generate-narration.sh",
        )
        val manifest = manifestFile.readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .associate { line -> line.substringBefore(' ') to line.substringAfter(' ') }
        for (line in tutorialNarration) {
            val recorded = manifest[line.id]
                ?: fail("Narration line '${line.id}' has no manifest entry — run scripts/generate-narration.sh")
            assertEquals(
                recorded,
                sha256(line.text),
                "Narration text '${line.id}' changed after its audio was generated — " +
                    "run scripts/generate-narration.sh and commit the new clips",
            )
            assertTrue(
                File(narrationDir, "${line.id}.mp3").exists(),
                "Missing clip ${line.id}.mp3 — run scripts/generate-narration.sh",
            )
        }
        assertEquals(
            tutorialNarration.map { it.id }.toSet(),
            manifest.keys,
            "Manifest lists clips for lines that no longer exist — run scripts/generate-narration.sh",
        )
    }

    /**
     * Not a test of behaviour: writes the current narration texts where the generation script
     * reads them (build/narration-texts.tsv). Running it is how the script extracts the texts
     * without parsing Kotlin.
     */
    @Test
    fun `dump narration texts for the generation script`() {
        val out = moduleFile("build/narration-texts.tsv")
        out.parentFile.mkdirs()
        out.writeText(tutorialNarration.joinToString("\n") { "${it.id}\t${it.text}" } + "\n")
        assertTrue(tutorialNarration.isNotEmpty())
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** Resolves [relative] against the shared module dir, whether tests run from it or the root. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir"))
        val direct = File(cwd, relative)
        if (direct.exists() || cwd.name == "shared") return direct
        return File(cwd, "shared/$relative")
    }
}
