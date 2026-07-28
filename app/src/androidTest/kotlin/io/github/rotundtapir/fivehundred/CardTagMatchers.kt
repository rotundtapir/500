// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher

/** Matches nodes whose testTag starts with [prefix]. */
fun hasTestTagPrefix(prefix: String) =
    SemanticsMatcher("testTag starts with '$prefix'") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

/**
 * cardkit's current tag on every card face `PlayingCard` draws: `ck:card:<label>`, keyed by the
 * card's label (`ck:card:J♠`) and never its code.
 *
 * Published as `io.github.rotundtapir.cardkit.ui.CardTestTagPrefix`. Not imported from there yet —
 * see [LEGACY_CARD_TAG_PREFIX].
 */
const val CARD_TAG_PREFIX = "ck:card:"

/**
 * What cardkit tagged card faces before rotundtapir/cardkit#9 namespaced them: a bare `card:`, which
 * collided with consumers' own card tags (euchre counted 76 matching nodes where 5 were wanted).
 *
 * This repo's `cardkit` submodule pin still predates that rename, so [cardFace] accepts either
 * prefix and the suites stay green on both sides of the pin bump. **When the pin advances past the
 * rename, drop this constant and its arm of [cardFace] and import [CARD_TAG_PREFIX] from
 * cardkit-ui.**
 */
const val LEGACY_CARD_TAG_PREFIX = "card:"

/** A card face drawn by cardkit's `PlayingCard`, whichever tag prefix the pinned cardkit emits. */
fun cardFace() = hasTestTagPrefix(CARD_TAG_PREFIX) or hasTestTagPrefix(LEGACY_CARD_TAG_PREFIX)
