// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NamesTest {

    private fun ok(raw: String): String {
        val r = Names.validate(raw)
        assertIs<Names.Result.Ok>(r, "expected '$raw' to be accepted, got $r")
        return r.name
    }

    private fun rejected(raw: String): Names.Rejection {
        val r = Names.validate(raw)
        assertIs<Names.Result.Rejected>(r, "expected '$raw' to be rejected, got $r")
        return r.reason
    }

    @Test
    fun `accepts a plain name and trims and collapses whitespace`() {
        assertEquals("Alice", ok("  Alice  "))
        assertEquals("Mary Jane", ok("Mary   Jane"))
        assertEquals("Anne-Marie", ok("Anne-Marie"))
        assertEquals("O'Brien", ok("O'Brien"))
    }

    @Test
    fun `enforces length bounds on the normalized name`() {
        assertEquals(Names.Rejection.TOO_SHORT, rejected("a"))
        assertEquals(Names.Rejection.TOO_SHORT, rejected("   x   "))
        assertEquals(Names.Rejection.TOO_LONG, rejected("x".repeat(Names.MAX_LENGTH + 1)))
        assertTrue(Names.isValid("x".repeat(Names.MAX_LENGTH)))
    }

    @Test
    fun `rejects the reserved bot suffix case and space insensitively`() {
        assertEquals(Names.Rejection.BOT_SUFFIX, rejected("Alice (bot)"))
        assertEquals(Names.Rejection.BOT_SUFFIX, rejected("Alice (BOT)"))
        assertEquals(Names.Rejection.BOT_SUFFIX, rejected("Alice (bot)   "))
    }

    @Test
    fun `rejects reserved system names and profanity and illegal characters`() {
        assertEquals(Names.Rejection.RESERVED, rejected("admin"))
        assertEquals(Names.Rejection.RESERVED, rejected("Server"))
        assertEquals(Names.Rejection.PROFANITY, rejected("shithead"))
        assertEquals(Names.Rejection.ILLEGAL_CHARS, rejected("bad<name>"))
        assertEquals(Names.Rejection.ILLEGAL_CHARS, rejected("emoji😀here"))
    }

    @Test
    fun `botLabel appends the suffix`() {
        assertEquals("Daisy (bot)", Names.botLabel("Daisy"))
        assertFalse(Names.isValid(Names.botLabel("Daisy")))
    }
}
