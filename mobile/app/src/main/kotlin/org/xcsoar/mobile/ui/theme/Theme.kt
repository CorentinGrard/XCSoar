// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.xcsoar.mobile.R

/**
 * Colours by function, never by hue (doc/architecture.rst, "User interface
 * guidelines"): red warning, orange caution, green safe, blue neutral
 * safe; lift green, sink copper orange; task ICAO magenta; route dark
 * purple-blue; updraft data sky blue.  Text stays monochrome.
 */
@Immutable
data class XcsColors(
    val warning: Color,
    val caution: Color,
    val safe: Color,
    val neutralSafe: Color,
    val lift: Color,
    val sink: Color,
    val task: Color,
    val route: Color,
    val updraft: Color,
    /** Values and labels: monochrome, maximum contrast. */
    val text: Color,
    val textSecondary: Color,
    /** Behind everything: the map's ground colour. */
    val background: Color,
    /** The bottom sheet / side panel holding the instruments. */
    val sheet: Color,
    /** InfoBoxes and control groups on the sheet. */
    val panel: Color,
    val panelBorder: Color,
    /** Cards floating over the map; opaque, or the shadow shows through. */
    val card: Color,
    /** Face of a button sitting on a [panel]. */
    val control: Color,
    /** Selected segment, primary action button. */
    val selected: Color,
    val onSelected: Color,
    /** The dark vario instrument, in every theme. */
    val instrument: Color,
    val instrumentText: Color,
    val instrumentTextSecondary: Color,
    val instrumentTrack: Color,
    val instrumentLift: Color,
    val instrumentSink: Color,
    /** Averaged climb (updraft data) on the instrument. */
    val instrumentUpdraft: Color,
    /** Alert banners: warning (inside airspace) and caution (ahead), with
        [onAlert] text at 4.5:1 or more in every theme. */
    val warningContainer: Color,
    val cautionContainer: Color,
    val onAlert: Color,
)

/**
 * Daylight: the "modern cockpit" design (pale terrain, white cards,
 * dark instrument).  The default in flight.  Every text colour has at
 * least 4.5:1 contrast on the surface it is drawn on.
 */
private val sunlight = XcsColors(
    warning = Color(0xFFB42A30),
    caution = Color(0xFFC2410C),
    safe = Color(0xFF0B7A5C),
    neutralSafe = Color(0xFF1F5BD6),
    lift = Color(0xFF0B7A5C),
    sink = Color(0xFFB5440F),
    task = Color(0xFFB5179E),
    route = Color(0xFF3F2A8C),
    updraft = Color(0xFF1A6FA8),
    text = Color(0xFF0E1210),
    textSecondary = Color(0xFF4A524C),
    background = Color(0xFFE9ECE3),
    sheet = Color(0xFFFFFFFF),
    panel = Color(0xFFF3F5F0),
    panelBorder = Color(0x1F0E1210),
    card = Color(0xFFFFFFFF),
    control = Color(0xFFFFFFFF),
    selected = Color(0xFF0E1210),
    onSelected = Color(0xFFFFFFFF),
    instrument = Color(0xFF0E1210),
    instrumentText = Color(0xFFFFFFFF),
    instrumentTextSecondary = Color(0xFFB9C2BB),
    instrumentTrack = Color(0xFF262C28),
    instrumentLift = Color(0xFF3BD1A6),
    instrumentSink = Color(0xFFFF9F6B),
    instrumentUpdraft = Color(0xFF7CC8F0),
    warningContainer = Color(0xFFB42A30),
    cautionContainer = Color(0xFFC2410C),
    onAlert = Color(0xFFFFFFFF),
)

/** Low light: dark surfaces, no pure white glare. */
private val night = XcsColors(
    warning = Color(0xFFFF6B6B),
    caution = Color(0xFFFFAB40),
    safe = Color(0xFF3BD1A6),
    neutralSafe = Color(0xFF82B1FF),
    lift = Color(0xFF3BD1A6),
    sink = Color(0xFFFF9F6B),
    task = Color(0xFFFF6EE6),
    route = Color(0xFF9FA8DA),
    updraft = Color(0xFF7CC8F0),
    text = Color(0xFFE6E6E6),
    textSecondary = Color(0xFFAAB2AC),
    background = Color(0xFF0B0D0C),
    sheet = Color(0xFF141816),
    panel = Color(0xFF1C211E),
    panelBorder = Color(0x33E6E6E6),
    card = Color(0xFF181D1A),
    control = Color(0xFF2A302C),
    selected = Color(0xFFE6E6E6),
    onSelected = Color(0xFF0B0D0C),
    instrument = Color(0xFF000000),
    instrumentText = Color(0xFFE6E6E6),
    instrumentTextSecondary = Color(0xFFAAB2AC),
    instrumentTrack = Color(0xFF262C28),
    instrumentLift = Color(0xFF3BD1A6),
    instrumentSink = Color(0xFFFF9F6B),
    instrumentUpdraft = Color(0xFF7CC8F0),
    warningContainer = Color(0xFF9F1F25),
    cautionContainer = Color(0xFF9A3412),
    onAlert = Color(0xFFFFFFFF),
)

/** Barlow for text, Barlow Condensed for numbers (bundled, SIL OFL). */
@Immutable
data class XcsFonts(val text: FontFamily, val numbers: FontFamily)

private val barlowFonts = XcsFonts(
    text = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_bold, FontWeight.Bold),
    ),
    numbers = FontFamily(
        Font(R.font.barlowcondensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlowcondensed_bold, FontWeight.Bold),
    ),
)

val LocalXcsColors = staticCompositionLocalOf { sunlight }

@Composable
fun XcsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) night else sunlight
    val scheme = if (dark)
        darkColorScheme(background = colors.background, surface = colors.sheet,
                        onBackground = colors.text, onSurface = colors.text)
    else
        lightColorScheme(background = colors.background, surface = colors.sheet,
                         onBackground = colors.text, onSurface = colors.text)

    val base = Typography()
    val typography = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = barlowFonts.text),
        bodyMedium = base.bodyMedium.copy(fontFamily = barlowFonts.text),
        bodySmall = base.bodySmall.copy(fontFamily = barlowFonts.text),
        labelLarge = base.labelLarge.copy(fontFamily = barlowFonts.text),
        labelMedium = base.labelMedium.copy(fontFamily = barlowFonts.text),
        titleMedium = base.titleMedium.copy(fontFamily = barlowFonts.text),
    )

    CompositionLocalProvider(LocalXcsColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

object XcsTheme {
    val colors: XcsColors
        @Composable get() = LocalXcsColors.current

    val fonts: XcsFonts
        get() = barlowFonts

    /** Numbers: condensed, tabular figures so digits don't jitter. */
    val numberStyle: TextStyle
        get() = TextStyle(fontFamily = fonts.numbers, fontWeight = FontWeight.SemiBold,
                          fontFeatureSettings = "tnum")
}
