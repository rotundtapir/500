// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

private val RED_SUIT = Color(0xFFE53935)

/**
 * Colors any card-suit symbols red (♥♦) or black (♠♣), leaving other characters in the ambient
 * text color. Android renders these characters through its color-emoji fallback, which produces
 * the same look; the web canvas has only our monochrome fallback font, so without explicit spans
 * the suits take the surrounding (usually white) text color.
 */
fun String.withSuitColors(): AnnotatedString = buildAnnotatedString {
    for (c in this@withSuitColors) {
        when (c) {
            '♥', '♦' -> withStyle(SpanStyle(color = RED_SUIT)) { append(c) }
            '♠', '♣' -> withStyle(SpanStyle(color = Color.Black)) { append(c) }
            else -> append(c)
        }
    }
}

/** [Text] for strings that may contain suit symbols (bid labels, contract lines, rules prose). */
@Composable
fun SuitText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current,
) {
    Text(
        text.withSuitColors(),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        maxLines = maxLines,
        style = style,
    )
}
