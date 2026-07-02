// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** House-rule toggles: disabling misère and/or no-trump bids removes them from the auction. */
class HouseRulesTest {

    private fun openerBids(rules: FiveHundredRules): List<Bid> {
        val state = rules.newGame(seed = 5)
        return rules.view(state, rules.currentActor(state)!!).legalBids
    }

    @Test
    fun `misere disabled removes both misere bids but nothing else`() {
        val bids = openerBids(FiveHundredRules(misereEnabled = false))
        assertFalse(Bid.Misere in bids)
        assertFalse(Bid.OpenMisere in bids)
        assertTrue(Bid.Named(10, Trump.NO_TRUMP) in bids)
        assertTrue(Bid.Named(6, Trump.SPADES) in bids)
        assertTrue(Bid.Pass in bids)
    }

    @Test
    fun `no-trumps disabled removes every NT level but keeps misere`() {
        val bids = openerBids(FiveHundredRules(noTrumpsEnabled = false))
        for (level in 6..10) assertFalse(Bid.Named(level, Trump.NO_TRUMP) in bids)
        assertTrue(Bid.Misere in bids)
        assertTrue(Bid.OpenMisere in bids)
        assertTrue(Bid.Named(10, Trump.HEARTS) in bids)
    }

    @Test
    fun `both disabled leaves only suited contracts`() {
        val bids = openerBids(FiveHundredRules(misereEnabled = false, noTrumpsEnabled = false))
        assertTrue(bids.filter { it != Bid.Pass }.all { it is Bid.Named && it.trump != Trump.NO_TRUMP })
        assertTrue(bids.size == 1 + 20, "Pass + 5 levels x 4 suits, got ${bids.size}")
    }

    @Test
    fun `a disabled bid is rejected even if submitted directly`() {
        val rules = FiveHundredRules(misereEnabled = false)
        val state = rules.newGame(seed = 5)
        val opener = rules.currentActor(state)!!
        assertThrows<IllegalArgumentException> {
            rules.apply(state, opener, Action.PlaceBid(Bid.Misere))
        }
    }

    @Test
    fun `defaults leave the full ladder biddable`() {
        val bids = openerBids(FiveHundredRules())
        assertTrue(Bid.Misere in bids)
        assertTrue(Bid.OpenMisere in bids)
        assertTrue(Bid.Named(6, Trump.NO_TRUMP) in bids)
    }
}
