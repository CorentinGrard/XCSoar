// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours by function, never by hue (doc/architecture.rst, "User interface
 * guidelines"): red warning, orange caution, green safe, blue neutral
 * safe; lift vibrant green, sink copper orange; task ICAO magenta; route
 * dark purple-blue; updraft data sky blue.  Text stays monochrome.
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
    val background: Color,
    val panel: Color,
    val panelBorder: Color,
)

/** High contrast for direct sunlight: the default in flight. */
private val sunlight = XcsColors(
    warning = Color(0xFFD50000),
    caution = Color(0xFFFF8F00),
    safe = Color(0xFF00A651),
    neutralSafe = Color(0xFF0057B7),
    lift = Color(0xFF00C853),
    sink = Color(0xFFB87333),
    task = Color(0xFFC800C8),
    route = Color(0xFF3F2A8C),
    updraft = Color(0xFF29B6F6),
    text = Color(0xFF000000),
    textSecondary = Color(0xFF424242),
    background = Color(0xFFFFFFFF),
    panel = Color(0xFFF2F2F2),
    panelBorder = Color(0xFF9E9E9E),
)

/** Low light: dark background, no pure white glare. */
private val night = XcsColors(
    warning = Color(0xFFFF5252),
    caution = Color(0xFFFFAB40),
    safe = Color(0xFF69F0AE),
    neutralSafe = Color(0xFF82B1FF),
    lift = Color(0xFF69F0AE),
    sink = Color(0xFFD7A07A),
    task = Color(0xFFFF6EFF),
    route = Color(0xFF9FA8DA),
    updraft = Color(0xFF81D4FA),
    text = Color(0xFFE6E6E6),
    textSecondary = Color(0xFFAAAAAA),
    background = Color(0xFF000000),
    panel = Color(0xFF121212),
    panelBorder = Color(0xFF424242),
)

val LocalXcsColors = staticCompositionLocalOf { sunlight }

@Composable
fun XcsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) night else sunlight
    val scheme = if (dark)
        darkColorScheme(background = colors.background, surface = colors.panel,
                        onBackground = colors.text, onSurface = colors.text)
    else
        lightColorScheme(background = colors.background, surface = colors.panel,
                         onBackground = colors.text, onSurface = colors.text)

    CompositionLocalProvider(LocalXcsColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object XcsTheme {
    val colors: XcsColors
        @Composable get() = LocalXcsColors.current
}
