// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Shared machinery for a speech bubble anchored to an on-screen element (the tutorial advice bubble
 * and the emote bubble both use it). [target] is the anchor's bounds in root coords; [overlayOrigin]
 * is the game overlay's origin, so the bubble is placed in overlay-local space. The bubble is
 * horizontally centred on the anchor (with a tail sliding to point at it) and vertically placed by
 * [yPlacement]; [tailDown] chooses whether the tail is under the bubble (pointing down at an anchor
 * below) or above it (pointing up at an anchor above).
 */
@Composable
internal fun BubbleLayout(
    target: Rect,
    overlayOrigin: Offset,
    tailDown: Boolean,
    maxWidth: Dp,
    yPlacement: (local: Rect, bubbleHeight: Int, gap: Int) -> Int,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var bubbleLeft by remember { mutableIntStateOf(0) }
    val local = target.translate(-overlayOrigin)
    val tailWidth = with(density) { TAIL_WIDTH.toPx() }
    val tailX = { (local.center.x - bubbleLeft - tailWidth / 2).roundToInt() }

    Layout(
        content = {
            Column {
                if (!tailDown) BubbleTail(pointUp = true, offsetX = tailX)
                content()
                if (tailDown) BubbleTail(pointUp = false, offsetX = tailX)
            }
        },
    ) { measurables, constraints ->
        val margin = with(density) { 8.dp.roundToPx() }
        val cap = minOf(constraints.maxWidth - margin * 2, with(density) { maxWidth.roundToPx() })
        val placeable = measurables[0].measure(
            Constraints(minWidth = 0, maxWidth = cap, minHeight = 0, maxHeight = constraints.maxHeight),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (local.center.x - placeable.width / 2f).roundToInt()
                .coerceIn(margin, (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin))
            val gap = with(density) { 2.dp.roundToPx() }
            val y = yPlacement(local, placeable.height, gap)
                .coerceIn(margin, (constraints.maxHeight - placeable.height - margin).coerceAtLeast(margin))
            bubbleLeft = x
            placeable.place(x, y)
        }
    }
}

/**
 * A speech bubble for an incoming emote, anchored to the sender's seat with a tail pointing at it.
 * Opponents sit at the top so their bubble drops below the avatar (tail up); the local player is at
 * the bottom, so [tailDown] puts the bubble above the hand (tail down).
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
            color = Color(0xFFFAFAFA),
            contentColor = MaterialTheme.colorScheme.primary,
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

private val TAIL_WIDTH = 26.dp
