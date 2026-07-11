// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Colors for content drawn directly on the table-green felt, or on the fixed light "card" surfaces
 * floated above it. The app's Material scheme serves the light dialog/card surfaces; most of the
 * chrome, though, sits straight on the dark felt in BOTH theme modes, where the scheme's defaults
 * fail two ways (2026-07-11 contrast audit):
 *
 * - On the felt, light-scheme roles (`primary`, on-surface tones, disabled onSurface at 38%) are
 *   dark-on-dark. Use [onBackground]-derived colors — [OnBackgroundOutlinedButton],
 *   [onBackgroundFieldColors], [feltSwitchColors], and `disabledContentColor = onBackground @ 38%`.
 * - On a FIXED light surface ([CardSurfaceWhite]) the content must be fixed too: theme `primary`
 *   flips to a pale green in dark mode (~2:1 on white). Use [InkOnCardSurface].
 */
internal val CardSurfaceWhite = Color(0xFFFAFAFA)

/** Fixed dark-green ink for content on [CardSurfaceWhite] — never the theme's mode-dependent primary. */
internal val InkOnCardSurface = Color(0xFF2E7D32)

/**
 * An outlined button legible on the felt: M3's default content color (`primary`) all but vanishes
 * against the green, so pin the content to `onBackground`.
 */
@Composable
internal fun OnBackgroundOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = onBackground),
        border = BorderStroke(1.dp, onBackground.copy(alpha = 0.5f)),
        modifier = modifier,
    ) { content() }
}

/**
 * Text-field colors legible on the felt: the M3 defaults draw the label and outline in dark
 * on-surface tones that all but vanish against it.
 */
@Composable
internal fun onBackgroundFieldColors(): TextFieldColors {
    val onBackground = MaterialTheme.colorScheme.onBackground
    // The scheme's `error` is a dark red (the theme is a light scheme with a dark background), so
    // error states get the M3 dark-scheme error red, which reads clearly on the green.
    val errorRed = Color(0xFFFFB4AB)
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = onBackground,
        unfocusedTextColor = onBackground,
        focusedLabelColor = onBackground,
        unfocusedLabelColor = onBackground.copy(alpha = 0.7f),
        focusedBorderColor = onBackground,
        unfocusedBorderColor = onBackground.copy(alpha = 0.5f),
        cursorColor = onBackground,
        focusedSupportingTextColor = onBackground.copy(alpha = 0.7f),
        unfocusedSupportingTextColor = onBackground.copy(alpha = 0.7f),
        errorTextColor = onBackground,
        errorLabelColor = errorRed,
        errorBorderColor = errorRed,
        errorSupportingTextColor = errorRed,
        errorCursorColor = errorRed,
    )
}

/**
 * Switch colors legible on the felt: the default checked track is theme `primary` — green on
 * green — and the unchecked track a light surface tone. Light thumb, readable track both ways.
 */
@Composable
internal fun feltSwitchColors(): SwitchColors {
    val onBackground = MaterialTheme.colorScheme.onBackground
    return SwitchDefaults.colors(
        checkedThumbColor = CardSurfaceWhite,
        checkedTrackColor = Color(0xFF66BB6A),
        uncheckedThumbColor = CardSurfaceWhite,
        uncheckedTrackColor = Color.Transparent,
        uncheckedBorderColor = onBackground.copy(alpha = 0.6f),
    )
}
