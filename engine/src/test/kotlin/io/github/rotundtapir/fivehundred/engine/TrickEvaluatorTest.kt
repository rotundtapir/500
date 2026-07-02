// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrickEvaluatorTest {
    private val spades = TrickEvaluator(Trump.SPADES)
    private val noTrump = TrickEvaluator(Trump.NO_TRUMP)

    private fun play(seat: Int, card: io.github.rotundtapir.cardkit.core.Card, nominated: Suit? = null) =
        TrickPlay(Seat(seat), card, nominated)

    @Test
    fun `bower and joker ordering in a suit contract`() {
        val jokerS = spades.strength(Joker, Suit.SPADES)
        val right = spades.strength(Rank.JACK of Suit.SPADES, Suit.SPADES)   // right bower
        val left = spades.strength(Rank.JACK of Suit.CLUBS, Suit.SPADES)     // left bower
        val aceTrump = spades.strength(Rank.ACE of Suit.SPADES, Suit.SPADES)
        assertTrue(jokerS > right && right > left && left > aceTrump)
    }

    @Test
    fun `left bower counts as the trump suit`() {
        val leftBower = Rank.JACK of Suit.CLUBS // with spades trump
        assertTrue(spades.isTrump(leftBower))
        assertTrue(spades.isLeftBower(leftBower))
        assertEquals(Suit.SPADES, spades.effectiveSuit(leftBower))
        // The Jack of clubs is NOT a club anymore for following purposes.
        assertFalse(spades.effectiveSuit(leftBower) == Suit.CLUBS)
    }

    @Test
    fun `any trump beats a high card of the led non-trump suit`() {
        // Hearts led, someone trumps with the 5 of spades.
        val trick = listOf(
            play(0, Rank.ACE of Suit.HEARTS),
            play(1, Rank.FIVE of Suit.SPADES),
            play(2, Rank.KING of Suit.HEARTS),
            play(3, Rank.QUEEN of Suit.HEARTS),
        )
        assertEquals(Seat(1), spades.winner(trick))
    }

    @Test
    fun `left bower beats the ace of trumps in a trick`() {
        val trick = listOf(
            play(0, Rank.ACE of Suit.SPADES),
            play(1, Rank.JACK of Suit.CLUBS), // left bower
        )
        assertEquals(Seat(1), spades.winner(trick))
    }

    @Test
    fun `highest of led suit wins when no trumps are played`() {
        val trick = listOf(
            play(0, Rank.NINE of Suit.HEARTS),
            play(1, Rank.ACE of Suit.HEARTS),
            play(2, Rank.SEVEN of Suit.DIAMONDS), // off-suit, cannot win
            play(3, Rank.KING of Suit.HEARTS),
        )
        assertEquals(Seat(1), spades.winner(trick))
    }

    @Test
    fun `must follow the led suit, and the left bower satisfies a trump lead`() {
        // Spades (trump) led. Hand holds the left bower (J clubs) + hearts. Must play the "spade".
        val hand = listOf(Rank.JACK of Suit.CLUBS, Rank.ACE of Suit.HEARTS, Rank.KING of Suit.HEARTS)
        val legal = spades.legalFollows(hand, ledSuit = Suit.SPADES)
        assertEquals(listOf(Rank.JACK of Suit.CLUBS), legal)
    }

    @Test
    fun `void in led suit may play anything`() {
        val hand = listOf(Rank.ACE of Suit.HEARTS, Rank.FIVE of Suit.SPADES)
        val legal = spades.legalFollows(hand, ledSuit = Suit.DIAMONDS)
        assertEquals(hand.toSet(), legal.toSet())
    }

    @Test
    fun `joker is the top card at no-trump and always playable`() {
        // Joker present, hearts led, hand also has a heart -> must-follow set, but joker still legal.
        val hand = listOf(Joker, Rank.KING of Suit.HEARTS, Rank.FIVE of Suit.SPADES)
        val legal = noTrump.legalFollows(hand, ledSuit = Suit.HEARTS)
        assertTrue(Joker in legal)
        assertTrue((Rank.KING of Suit.HEARTS) in legal)
        assertFalse((Rank.FIVE of Suit.SPADES) in legal) // off-suit, not joker
    }

    @Test
    fun `joker wins at no-trump`() {
        val trick = listOf(
            play(0, Rank.ACE of Suit.HEARTS),
            play(1, Joker),
            play(2, Rank.KING of Suit.HEARTS),
        )
        assertEquals(Seat(1), noTrump.winner(trick))
    }

    @Test
    fun `six-handed ranks slot between ten and jack within trumps`() {
        // Hearts trump: Joker > J♥ (right) > J♦ (left) > A > K > Q > 13 > 12 > 11 > 10 of hearts.
        val hearts = TrickEvaluator(Trump.HEARTS)
        val highToLow = listOf(
            Joker,
            Rank.JACK of Suit.HEARTS,   // right bower
            Rank.JACK of Suit.DIAMONDS, // left bower
            Rank.ACE of Suit.HEARTS,
            Rank.KING of Suit.HEARTS,
            Rank.QUEEN of Suit.HEARTS,
            Rank.THIRTEEN of Suit.HEARTS,
            Rank.TWELVE of Suit.HEARTS,
            Rank.ELEVEN of Suit.HEARTS,
            Rank.TEN of Suit.HEARTS,
        )
        val strengths = highToLow.map { hearts.strength(it, Suit.HEARTS) }
        assertEquals(strengths, strengths.sortedDescending(), "expected strictly descending order")
        assertEquals(strengths.size, strengths.toSet().size, "strengths must be distinct")

        // The queen beats the 13 in a trick, and the 13 beats the 12.
        assertEquals(Seat(1), hearts.winner(listOf(play(0, Rank.THIRTEEN of Suit.HEARTS), play(1, Rank.QUEEN of Suit.HEARTS))))
        assertEquals(Seat(0), hearts.winner(listOf(play(0, Rank.THIRTEEN of Suit.HEARTS), play(1, Rank.TWELVE of Suit.HEARTS))))
        // The left bower still beats every plain trump, 13 included.
        assertEquals(Seat(1), hearts.winner(listOf(play(0, Rank.THIRTEEN of Suit.HEARTS), play(1, Rank.JACK of Suit.DIAMONDS))))
    }

    @Test
    fun `six-handed ranks slot between ten and jack at no-trump`() {
        // A > K > Q > J > 13 > 12 > 11 > 10 of the led suit.
        val highToLow = listOf(
            Rank.ACE of Suit.SPADES,
            Rank.KING of Suit.SPADES,
            Rank.QUEEN of Suit.SPADES,
            Rank.JACK of Suit.SPADES,
            Rank.THIRTEEN of Suit.SPADES,
            Rank.TWELVE of Suit.SPADES,
            Rank.ELEVEN of Suit.SPADES,
            Rank.TEN of Suit.SPADES,
        )
        val strengths = highToLow.map { noTrump.strength(it, Suit.SPADES) }
        assertEquals(strengths, strengths.sortedDescending(), "expected strictly descending order")
        assertEquals(strengths.size, strengths.toSet().size, "strengths must be distinct")

        assertEquals(
            Seat(2),
            noTrump.winner(
                listOf(
                    play(0, Rank.THIRTEEN of Suit.SPADES),
                    play(1, Rank.ELEVEN of Suit.SPADES),
                    play(2, Rank.ACE of Suit.SPADES),
                ),
            ),
        )
    }

    @Test
    fun `joker led at no-trump nominates the suit others must follow`() {
        val trick = listOf(
            play(0, Joker, nominated = Suit.CLUBS),
            play(1, Rank.ACE of Suit.CLUBS),
        )
        // Joker still wins, and the led suit was clubs.
        assertEquals(Suit.CLUBS, noTrump.ledSuitOf(trick.first()))
        assertEquals(Seat(0), noTrump.winner(trick))
    }
}
