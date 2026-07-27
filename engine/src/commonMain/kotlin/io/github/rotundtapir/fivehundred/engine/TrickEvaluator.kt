// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.CardColor
import io.github.rotundtapir.cardkit.core.JokerRole

/**
 * One card played to a trick, by a seat, optionally nominating a suit (Joker led at no-trump).
 *
 * Now lives in cardkit-core; the alias keeps this engine package as the import surface. The wire
 * format is unchanged — kotlinx.serialization serializes the class structurally with the same
 * field names.
 */
typealias TrickPlay = io.github.rotundtapir.cardkit.core.TrickPlay

/**
 * Encapsulates the card-ranking rules of 500, including the two bowers and the Joker.
 *
 * Now provided by cardkit-core's generalized evaluator; the alias plus the [TrickEvaluator]
 * factory below keep every `TrickEvaluator(trump)` call site compiling unchanged.
 */
typealias TrickEvaluator = io.github.rotundtapir.cardkit.core.TrickEvaluator

/**
 * The cardkit [TrickEvaluator] configured for a 500 [trump] denomination.
 *
 * In a suit contract the Joker is the highest trump and the trump order (high→low) is: Joker,
 * right bower (Jack of the trump suit), left bower (Jack of the same-colour suit), then
 * A K Q 10 9 … of the trump suit. The left bower counts as a member of the trump suit, not its
 * printed suit. At no-trump the Joker is the sole trump and otherwise the highest card of the led
 * suit wins.
 */
fun TrickEvaluator(trump: Trump): TrickEvaluator = TrickEvaluator(
    trumpSuit = trump.suit,
    jokerRole = if (trump == Trump.NO_TRUMP) JokerRole.SOLE_TRUMP else JokerRole.HIGHEST_TRUMP,
)

/** The colour of the [Trump] suit, or `null` at no-trump. */
val Trump.color: CardColor?
    get() = suit?.color
