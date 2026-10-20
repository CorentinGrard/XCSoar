// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sunlight contrast audit (mobile/docs/PLAN.md M7), kept as a test:
 * WCAG contrast of each text colour on the surfaces it is drawn on, in
 * both themes.  Values and labels at 7:1, everything else 4.5:1.
 */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun check(theme: String, colors: XcsColors) {
        val surfaces = mapOf("sheet" to colors.sheet, "panel" to colors.panel,
                             "card" to colors.card, "control" to colors.control)
        val texts = mapOf(
            "text" to (colors.text to 7.0), "textSecondary" to (colors.textSecondary to 4.5),
            // functional colours, as values or labels on cards and tiles
            "warning" to (colors.warning to 4.5), "caution" to (colors.caution to 4.5),
            "safe" to (colors.safe to 4.5), "neutralSafe" to (colors.neutralSafe to 4.5),
            "lift" to (colors.lift to 4.5), "sink" to (colors.sink to 4.5),
            "task" to (colors.task to 4.5))
        val failures = buildList {
            for ((name, pair) in texts) for ((surface, background) in surfaces) {
                val r = ratio(pair.first, background)
                if (r < pair.second) add("$name on $surface %.2f < %.1f".format(r, pair.second))
            }
            // the map's ground carries the primary text only (chips are cards)
            ratio(colors.text, colors.background).let { if (it < 7.0) add("text on background") }
            for ((name, pair) in mapOf(
                    "onSelected" to (colors.onSelected to colors.selected),
                    "onAlert on warning" to (colors.onAlert to colors.warningContainer),
                    "onAlert on caution" to (colors.onAlert to colors.cautionContainer))) {
                val minimum = if (name == "onSelected") 7.0 else 4.5
                val r = ratio(pair.first, pair.second)
                if (r < minimum) add("$name %.2f < %.1f".format(r, minimum))
            }
        }
        assertTrue("$theme:\n  " + failures.joinToString("\n  "), failures.isEmpty())
    }

    @Test
    fun sunlight() = check("sunlight", sunlight)

    @Test
    fun night() = check("night", night)
}
