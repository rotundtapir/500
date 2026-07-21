// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.Contract
import io.github.rotundtapir.fivehundred.engine.HandResult
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for the hand-result header tint: it must follow the contract outcome from the
 * human's perspective, never the sign of the human team's point delta. With 4 players and 2 teams,
 * seat 0 (the human) is on team 0 and seats 1/3 are on team 1.
 */
class HandResultTintTest {

    private fun result(declarer: Seat, made: Boolean, teamDeltas: Map<Int, Int>) = HandResult(
        contract = Contract(declarer, Bid.Named(6, Trump.DIAMONDS)),
        declarerTricks = if (made) 7 else 5,
        made = made,
        teamDeltas = teamDeltas,
    )

    @Test
    fun `opponents making their contract is red even when we gained trick points`() {
        // The exact bug: defenders took 3 tricks (+30), so a delta-based tint showed a green
        // "Frank made 6♦!" banner over a hand the opponents won.
        val r = result(declarer = Seat(1), made = true, teamDeltas = mapOf(0 to 30, 1 to 80))
        assertFalse(handWentMyWay(r, myTeam = 0, teamCount = 2))
    }

    @Test
    fun `opponents failing their contract is green even when we took no tricks`() {
        val r = result(declarer = Seat(1), made = false, teamDeltas = mapOf(0 to 0, 1 to -80))
        assertTrue(handWentMyWay(r, myTeam = 0, teamCount = 2))
    }

    @Test
    fun `our side making its contract is green`() {
        val r = result(declarer = Seat(0), made = true, teamDeltas = mapOf(0 to 80, 1 to 30))
        assertTrue(handWentMyWay(r, myTeam = 0, teamCount = 2))
    }

    @Test
    fun `our side failing its contract is red`() {
        val r = result(declarer = Seat(2), made = false, teamDeltas = mapOf(0 to -80, 1 to 30))
        assertFalse(handWentMyWay(r, myTeam = 0, teamCount = 2))
    }
}
