// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Suit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The denomination of a suit/no-trump bid, in ascending bidding order. */
enum class Trump(val suit: Suit?, val symbol: String) {
    SPADES(Suit.SPADES, "♠"),
    CLUBS(Suit.CLUBS, "♣"),
    DIAMONDS(Suit.DIAMONDS, "♦"),
    HEARTS(Suit.HEARTS, "♥"),
    NO_TRUMP(null, "NT");

    val isNoTrump: Boolean get() = this == NO_TRUMP
}

/** A bid a player can make during the auction. */
@Serializable
sealed interface Bid {
    /** Pass. Not part of the ranked bid ladder. */
    @Serializable
    @SerialName("pass")
    data object Pass : Bid

    /** A contract to win [level] (6..10) tricks in a given [trump]. */
    @Serializable
    @SerialName("named")
    data class Named(val level: Int, val trump: Trump) : Bid {
        init {
            require(level in 6..10) { "Bid level must be 6..10, was $level" }
        }
    }

    /** Misère: a contract to win **no** tricks. The bidder's partner sits out. */
    @Serializable
    @SerialName("misere")
    data object Misere : Bid

    /** Open Misère: as [Misere] but the bidder's hand is exposed. The highest possible bid. */
    @Serializable
    @SerialName("openMisere")
    data object OpenMisere : Bid
}

/** A short label such as `"8♥"`, `"Misère"`, `"Open Misère"`, `"Pass"`. */
val Bid.label: String
    get() = when (this) {
        Bid.Pass -> "Pass"
        Bid.Misere -> "Misère"
        Bid.OpenMisere -> "Open Misère"
        is Bid.Named -> "$level${trump.symbol}"
    }

/**
 * The scoring schedule mapping each contract to its point value and its position in the bid ladder.
 *
 * Defaults to the widely-used **Avondale schedule**: level 6 bids are 40/60/80/100/120 for
 * ♠/♣/♦/♥/NT, rising by 100 per extra trick; Misère is 250 (ranking between 8♠ and 8♣ by value);
 * Open Misère is 500 and ranks as the highest bid of all. It is a class (rather than an object) so
 * an alternative schedule seam can be introduced later without changing consumers.
 */
class ScoreSchedule {
    /** The point value of a contract if made. */
    fun value(bid: Bid): Int = when (bid) {
        Bid.Pass -> 0
        Bid.Misere -> 250
        Bid.OpenMisere -> 500
        is Bid.Named -> 40 + 100 * (bid.level - 6) + 20 * bid.trump.ordinal
    }

    /**
     * All biddable contracts (excluding [Bid.Pass]) in ascending rank order. A bid outranks another
     * iff it appears later in this list.
     */
    val ladder: List<Bid> = buildList {
        val named = buildList {
            for (level in 6..10) for (trump in Trump.entries) add(Bid.Named(level, trump))
        }
        // Named bids plus Misère, ordered by point value (this places Misère between 8♠ and 8♣)...
        addAll((named + Bid.Misere).sortedBy { value(it) })
        // ...then Open Misère as the strictly highest bid (its 500 value ties 10♥, so rank it explicitly).
        add(Bid.OpenMisere)
    }

    private val rankByBid: Map<Bid, Int> = ladder.withIndex().associate { (i, bid) -> bid to i }

    /** The rank (ladder index) of a bid; higher is stronger. [Bid.Pass] is `-1`. */
    fun rank(bid: Bid): Int = rankByBid[bid] ?: -1

    /** Whether [bid] outranks [than] (both must be non-pass bids). */
    fun outranks(bid: Bid, than: Bid): Boolean = rank(bid) > rank(than)

    companion object {
        val Avondale = ScoreSchedule()
    }
}
