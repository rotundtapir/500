// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import io.github.rotundtapir.cardkit.ui.CardTestTagPrefix

/** Matches nodes whose testTag starts with [prefix]. */
fun hasTestTagPrefix(prefix: String) =
    SemanticsMatcher("testTag starts with '$prefix'") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

/**
 * A card face drawn by cardkit's `PlayingCard`, matched by cardkit-ui's published
 * [CardTestTagPrefix] (`ck:card:<label>`, keyed by the card's label and never its code). The
 * transitional arm for the pre-rename `card:` prefix was dropped once the submodule pin passed
 * rotundtapir/cardkit#9.
 */
fun cardFace() = hasTestTagPrefix(CardTestTagPrefix)
