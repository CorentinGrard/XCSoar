// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.xcsoar.mobile.core.TileGraphic
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsColors
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The thermal assistant around the glider while circling: where on its
 * circle the climb was stronger, in the map's directions.  The map
 * turns with [upBearing] (the direction at the top of the screen).
 */
@Composable
fun ThermalAssistantOverlay(
    graphic: TileGraphic,
    centre: Offset,
    radius: Dp,
    upBearing: Double,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    val strongest = strongestBearing(graphic)
    Canvas(modifier.clearAndSetSemantics {
        contentDescription = strongest?.let { "Thermal assistant: strongest lift " +
            "${Format.bearing(it).text}°" } ?: "Thermal assistant"
    }) {
        thermalAssistant(graphic, centre, radius.toPx(), upBearing, colors, halo = true)
    }
}

/**
 * ThermalAssistantRenderer: the climb measured on each heading of the
 * circle ([TileGraphic.lift], from north in 10° steps), placed where the
 * glider was then (90° left or right of that heading from the circle's
 * centre).  No lift on the inner circle, the most lift on the outer
 * one; the line points to the stronger side.
 *
 * @param upBearing the direction at the top: the map's, or the
 * heading in a tile (upstream's gauge)
 */
internal fun DrawScope.thermalAssistant(g: TileGraphic, centre: Offset, radius: Float,
                                        upBearing: Double, colors: XcsColors,
                                        halo: Boolean = false) {
    val points = liftPoints(g, centre, radius, upBearing) ?: return
    val ring = colors.text.copy(alpha = 0.5f)
    val width = 1.5.dp.toPx()
    if (halo) {
        // readable over any terrain
        drawCircle(colors.sheet.copy(alpha = 0.5f), radius, centre, style = Stroke(width * 3))
        drawCircle(colors.sheet.copy(alpha = 0.5f), radius / 2, centre, style = Stroke(width * 3))
    }
    drawCircle(ring, radius, centre, style = Stroke(width))
    drawCircle(ring, radius / 2, centre, style = Stroke(width))

    val polygon = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }
    drawPath(polygon, colors.updraft.copy(alpha = 0.3f))
    drawPath(polygon, colors.updraft, style = Stroke(width * 1.5f, join = StrokeJoin.Round))

    // the advisor: towards the average of the points
    val average = Offset(points.map { it.x }.average().toFloat(),
                         points.map { it.y }.average().toFloat())
    drawLine(colors.updraft, centre, average, width * 2, StrokeCap.Round)
}

/** The polygon's corners on screen; null without the core's 36 values. */
internal fun liftPoints(g: TileGraphic, centre: Offset, radius: Float,
                        upBearing: Double): List<Offset>? {
    val lift = g.lift.takeIf { it.size == LIFT_SECTORS } ?: return null
    val maxLift = max(1.0, lift.max())
    return lift.mapIndexed { i, v ->
        // NormalizeLift(): -max on the centre, 0 halfway, max outside
        val r = radius * ((v + maxLift) / (2 * maxLift)).coerceIn(0.0, 1.0)
        val bearing = i * 360.0 / LIFT_SECTORS + if (g.left) 90.0 else -90.0
        val a = Math.toRadians(bearing - upBearing)
        Offset(centre.x + (r * sin(a)).toFloat(), centre.y - (r * cos(a)).toFloat())
    }
}

/** Where on the circle the climb was strongest, degrees true; null if nowhere. */
internal fun strongestBearing(g: TileGraphic): Double? {
    val lift = g.lift.takeIf { it.size == LIFT_SECTORS } ?: return null
    val i = lift.indices.maxByOrNull { lift[it] }?.takeIf { lift[it] > 0 } ?: return null
    return ((i * 360.0 / LIFT_SECTORS + if (g.left) 90.0 else -90.0) % 360 + 360) % 360
}

/** XCSoar's LiftDatabase: one value per 10° of heading. */
private const val LIFT_SECTORS = 36
