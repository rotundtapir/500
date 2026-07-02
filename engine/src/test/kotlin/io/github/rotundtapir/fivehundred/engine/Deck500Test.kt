// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.CardColor
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Deck500Test {
    private val suited = fiveHundredDeck.filterIsInstance<SuitedCard>()

    @Test
    fun `deck has exactly 43 cards`() {
        assertEquals(43, fiveHundredDeck.size)
        assertEquals(43, fiveHundredDeck.toSet().size) // all distinct
    }

    @Test
    fun `red suits run 4 to Ace, black suits run 5 to Ace`() {
        for (suit in listOf(Suit.HEARTS, Suit.DIAMONDS)) {
            assertEquals(11, suited.count { it.suit == suit })
            assertTrue(suited.any { it.suit == suit && it.rank == Rank.FOUR })
        }
        for (suit in listOf(Suit.SPADES, Suit.CLUBS)) {
            assertEquals(10, suited.count { it.suit == suit })
            assertTrue(suited.none { it.suit == suit && it.rank == Rank.FOUR })
        }
    }

    @Test
    fun `no twos or threes anywhere, no black fours, exactly one joker`() {
        assertTrue(suited.none { it.rank == Rank.TWO || it.rank == Rank.THREE })
        assertTrue(suited.none { it.rank == Rank.FOUR && it.color == CardColor.BLACK })
        assertEquals(1, fiveHundredDeck.count { it is Joker })
    }

    @Test
    fun `deals 10 to each of 4 players with a 3-card kitty`() {
        assertEquals(4 * HAND_SIZE + KITTY_SIZE, fiveHundredDeck.size)
    }

    @Test
    fun `the 2- and 4-player decks are the 43-card deck`() {
        assertEquals(fiveHundredDeck, fiveHundredDeck(2))
        assertEquals(fiveHundredDeck, fiveHundredDeck(4))
        assertThrows<IllegalArgumentException> { fiveHundredDeck(3) }
        assertThrows<IllegalArgumentException> { fiveHundredDeck(5) }
    }

    @Test
    fun `the 6-player deck is exactly the full 52 plus 11s, 12s, red 13s and one joker`() {
        val deck = fiveHundredDeck(6)
        assertEquals(63, deck.size)
        assertEquals(63, deck.toSet().size) // all distinct, so the multiset is one of each

        val expected: Set<Card> = buildSet {
            for (suit in Suit.entries) {
                for (rank in Rank.entries) {
                    if (rank == Rank.THIRTEEN && suit.color == CardColor.BLACK) continue // no black 13s
                    add(SuitedCard(rank, suit))
                }
            }
            add(Joker)
        }
        assertEquals(expected, deck.toSet())

        // Deals 10 to each of 6 players with a 3-card kitty and nothing left over.
        assertEquals(6 * HAND_SIZE + KITTY_SIZE, deck.size)
    }
}
