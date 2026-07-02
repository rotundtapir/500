// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.buildDeck
import io.github.rotundtapir.cardkit.core.rangeTo

/** The 11/12/13 ranks exist only in the six-handed deck; every other deck excludes them. */
private val sixHandedRanks = setOf(Rank.ELEVEN, Rank.TWELVE, Rank.THIRTEEN)

/**
 * The 43-card deck for 2- and 4-player 500: 4→A of the red suits (11 each), 5→A of the black suits
 * (10 each), plus a single Joker. Equivalent to a standard deck with the 2s, 3s and black 4s removed
 * and a Joker added. (The 11/12/13 ranks, which [Rank] declares between TEN and JACK for the
 * six-handed game, are excluded here.)
 */
val fiveHundredDeck: List<Card> = buildDeck {
    suits(Suit.HEARTS, Suit.DIAMONDS) { ranks((Rank.FOUR..Rank.ACE) - sixHandedRanks) }
    suits(Suit.SPADES, Suit.CLUBS) { ranks((Rank.FIVE..Rank.ACE) - sixHandedRanks) }
    joker()
}

/**
 * The 63-card deck for 6-player 500: a full standard 52 plus the 11s and 12s of every suit, the 13s
 * of the red suits only, and a single Joker. Deals 6 × 10 with a 3-card kitty exactly. The 11/12/13
 * ranks sit between the 10 and the Jack in strength (they are declared there in [Rank]).
 */
val fiveHundredDeckSixHanded: List<Card> = buildDeck {
    // Rank.TWO..Rank.ACE includes ELEVEN/TWELVE/THIRTEEN, which sit between TEN and JACK.
    suits(Suit.HEARTS, Suit.DIAMONDS) { ranks(Rank.TWO..Rank.ACE) }
    suits(Suit.SPADES, Suit.CLUBS) { ranks((Rank.TWO..Rank.ACE) - Rank.THIRTEEN) }
    joker()
}

/**
 * The deck used at [playerCount] players: 43 cards for 2 and 4 players (at 2, twenty cards are left
 * dead after the deal), 63 cards for 6.
 */
fun fiveHundredDeck(playerCount: Int): List<Card> = when (playerCount) {
    2, 4 -> fiveHundredDeck
    6 -> fiveHundredDeckSixHanded
    else -> throw IllegalArgumentException("500 is played by 2, 4 or 6 players, not $playerCount")
}

const val HAND_SIZE = 10
const val KITTY_SIZE = 3
const val TRICKS_PER_HAND = 10

/** The score at which a partnership wins (or, negated, loses "out the back door"). */
const val WINNING_SCORE = 500
