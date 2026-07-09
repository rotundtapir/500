// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.CardColor
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import kotlinx.serialization.Serializable

/** One card played to a trick, by a seat, optionally nominating a suit (Joker led at no-trump). */
@Serializable
data class TrickPlay(val seat: io.github.rotundtapir.cardkit.core.Seat, val card: Card, val nominated: Suit? = null)

/**
 * Encapsulates the card-ranking rules of 500 for a given [trump] denomination, including the two
 * bowers and the Joker.
 *
 * In a suit contract the trump order (high→low) is: Joker, right bower (Jack of the trump suit),
 * left bower (Jack of the same-colour suit), then A K Q 10 9 … of the trump suit. The left bower
 * counts as a member of the trump suit, not its printed suit. At no-trump the Joker is the sole
 * trump and otherwise the highest card of the led suit wins.
 */
class TrickEvaluator(val trump: Trump) {

    private val trumpSuit: Suit? = trump.suit

    /** The suit of the Jack that acts as the left bower for [suit] (the other suit of the same colour). */
    private fun leftBowerSuit(suit: Suit): Suit = when (suit) {
        Suit.SPADES -> Suit.CLUBS
        Suit.CLUBS -> Suit.SPADES
        Suit.HEARTS -> Suit.DIAMONDS
        Suit.DIAMONDS -> Suit.HEARTS
    }

    fun isRightBower(card: Card): Boolean =
        trumpSuit != null && card is SuitedCard && card.rank == Rank.JACK && card.suit == trumpSuit

    fun isLeftBower(card: Card): Boolean =
        trumpSuit != null && card is SuitedCard && card.rank == Rank.JACK &&
            card.suit == leftBowerSuit(trumpSuit)

    /** Whether [card] is a trump: any trump-suit card (incl. both bowers) in a suit contract, or the Joker. */
    fun isTrump(card: Card): Boolean = when {
        card is Joker -> true // Joker is a trump in every contract (the sole trump at no-trump)
        trumpSuit == null -> false
        isLeftBower(card) -> true
        card is SuitedCard -> card.suit == trumpSuit
        else -> false
    }

    /**
     * The effective suit of [card]: the suit it "belongs to" for following. The left bower belongs to
     * the trump suit; the Joker belongs to the trump suit in a suit contract and to no suit (`null`) at
     * no-trump.
     */
    fun effectiveSuit(card: Card): Suit? = when {
        card is Joker -> trumpSuit // null at no-trump
        isLeftBower(card) -> trumpSuit
        card is SuitedCard -> card.suit
        else -> null
    }

    /**
     * A comparable strength for [card] within a trick whose led suit is [ledSuit]. Higher wins; a card
     * that cannot win (off-suit and non-trump) scores below every eligible card.
     */
    fun strength(card: Card, ledSuit: Suit?): Int {
        if (trump != Trump.NO_TRUMP) {
            if (isTrump(card)) {
                return when {
                    card is Joker -> 1000
                    isRightBower(card) -> 900
                    isLeftBower(card) -> 800
                    card is SuitedCard -> 100 + card.rank.ordinal
                    else -> 100
                }
            }
            // Non-trump card: only wins if it is of the led suit.
            return if (card is SuitedCard && card.suit == ledSuit) card.rank.ordinal else -1
        }
        // No-trump.
        return when {
            card is Joker -> 1000
            card is SuitedCard && card.suit == ledSuit -> card.rank.ordinal
            else -> -1
        }
    }

    /**
     * The led suit established by the first play of a trick. Normally the effective suit of the led
     * card; if the Joker is led at no-trump it is the [TrickPlay.nominated] suit (or `null` if the
     * leader named none, leaving following unconstrained).
     */
    fun ledSuitOf(firstPlay: TrickPlay): Suit? = when {
        firstPlay.card is Joker && trump == Trump.NO_TRUMP -> firstPlay.nominated
        else -> effectiveSuit(firstPlay.card)
    }

    /** The seat that wins a completed [trick] (played in order, at least one play). */
    fun winner(trick: List<TrickPlay>): io.github.rotundtapir.cardkit.core.Seat {
        require(trick.isNotEmpty()) { "Empty trick has no winner" }
        val ledSuit = ledSuitOf(trick.first())
        return trick.maxBy { strength(it.card, ledSuit) }.seat
    }

    /**
     * The legal cards from [hand] when [ledSuit] has been led (pass `null` only for the no-trump
     * Joker-led-without-nomination case, which leaves play unconstrained). Players must follow the led
     * suit if able; when void they may play anything. At no-trump the Joker may always be played.
     */
    fun legalFollows(hand: List<Card>, ledSuit: Suit?): List<Card> {
        if (ledSuit == null) return hand
        val following = hand.filter { effectiveSuit(it) == ledSuit }
        if (following.isEmpty()) return hand
        return if (trump == Trump.NO_TRUMP) {
            following + hand.filter { it is Joker } // Joker always playable at no-trump
        } else {
            following
        }
    }
}

/** The colour of the [Trump] suit, or `null` at no-trump. */
val Trump.color: CardColor?
    get() = suit?.color
