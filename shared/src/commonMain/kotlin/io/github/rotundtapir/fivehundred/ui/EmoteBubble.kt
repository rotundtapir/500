// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rotundtapir.cardkit.ui.felt.CardSurfaceWhite
import io.github.rotundtapir.cardkit.ui.felt.InkOnCardSurface
import io.github.rotundtapir.cardkit.ui.tutorial.BubbleLayout
import kotlin.math.roundToInt

/**
 * A speech bubble for an incoming emote, anchored to the sender's seat with a tail pointing at it
 * (cardkit's [BubbleLayout], shared with the tutorial bubble). Opponents sit at the top so their
 * bubble drops below the avatar (tail up); the local player is at the bottom, so [tailDown] puts
 * the bubble above the hand (tail down).
 */
@Composable
internal fun EmoteBubble(
    target: Rect,
    overlayOrigin: Offset,
    text: String,
    tailDown: Boolean,
) {
    BubbleLayout(
        target = target,
        overlayOrigin = overlayOrigin,
        tailDown = tailDown,
        maxWidth = 320.dp,
        yPlacement = { local, height, gap ->
            if (tailDown) (local.top - height - gap).roundToInt() else (local.bottom + gap).roundToInt()
        },
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = CardSurfaceWhite,
            contentColor = InkOnCardSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.testTag("emoteBubble"),
        ) {
            Text(
                text,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
