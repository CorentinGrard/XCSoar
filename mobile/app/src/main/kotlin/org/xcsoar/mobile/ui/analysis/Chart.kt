// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.ChartPoint
import org.xcsoar.mobile.core.TaskLeg
import org.xcsoar.mobile.core.Trend
import org.xcsoar.mobile.core.UnitInfo
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Grid steps for the charts: round numbers, a handful of lines. */
object ChartAxis {
    /** 1, 2 or 5 × 10ⁿ, so that [span] needs at most [maxLines] steps. */
    fun niceStep(span: Double, maxLines: Int = 5): Double {
        if (span <= 0) return 1.0
        val raw = span / maxLines
        val magnitude = 10.0.pow(floor(log10(raw)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    }

    /** A grid value with as many decimals as [step] needs: "500", "0.2". */
    fun label(value: Double, step: Double): String {
        val decimals = if (step >= 1) 0 else ceil(-log10(step) - 1e-9).toInt()
        // no "-0"
        val v = if (abs(value) < step / 2) 0.0 else value
        return String.format(Locale.ROOT, "%.${decimals}f", v)
    }

    /** Hours between the time grid lines: 15 min to 4 h, at most 6 lines. */
    fun timeStep(hours: Double) =
        listOf(0.25, 0.5, 1.0, 2.0, 4.0).firstOrNull { hours / it <= 6 } ?: 8.0
}

/** Converts chart values (hours, SI) to pixels. */
class ChartMapper(
    private val left: Float, private val top: Float,
    private val width: Float, private val height: Float,
    val xMax: Double, val yMin: Double, val yMax: Double,
) {
    fun x(t: Double) = left + (t / xMax * width).toFloat()
    fun y(v: Double) = top + height - ((v - yMin) / (yMax - yMin) * height).toFloat()
    fun at(p: ChartPoint) = Offset(x(p.t), y(p.y))
    val bottom get() = top + height
    val right get() = left + width
    val topY get() = top
    val leftX get() = left
}

private val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))

fun DrawScope.line(m: ChartMapper, points: List<ChartPoint>, color: Color,
                   width: Float = 3f, dashed: Boolean = false) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(m.at(points[0]).x, m.at(points[0]).y)
        for (p in points.drop(1)) lineTo(m.at(p).x, m.at(p).y)
    }
    drawPath(path, color, style = Stroke(width, pathEffect = if (dashed) dash else null))
}

/** The area below [points] down to the chart's bottom (terrain). */
fun DrawScope.area(m: ChartMapper, points: List<ChartPoint>, color: Color) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(m.at(points[0]).x, m.bottom)
        for (p in points) lineTo(m.at(p).x, m.at(p).y)
        lineTo(m.at(points.last()).x, m.bottom)
        close()
    }
    drawPath(path, color)
}

/** A trend line across the whole chart, as upstream's DrawTrend(). */
fun DrawScope.trend(m: ChartMapper, trend: Trend, color: Color) =
    line(m, listOf(ChartPoint(0.0, trend.at(0.0)), ChartPoint(m.xMax, trend.at(m.xMax))),
         color, width = 2f, dashed = true)

/** A horizontal reference line (MacCready, estimated speed). */
fun DrawScope.level(m: ChartMapper, y: Double, color: Color) =
    line(m, listOf(ChartPoint(0.0, y), ChartPoint(m.xMax, y)), color, width = 2f, dashed = true)

/**
 * A chart over the flight time, like XCSoar's analysis charts: time
 * grid in hours, value grid in the pilot's [unit].  [draw] paints the
 * data; task points reached ([legs]) are marked with their number.
 * Shows [noData] instead when [hasData] is false.
 */
@Composable
fun TimeChart(
    description: String,
    hasData: Boolean,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    unit: UnitInfo,
    legs: List<TaskLeg>,
    modifier: Modifier = Modifier,
    labels: List<Pair<String, Double>> = emptyList(),
    draw: DrawScope.(ChartMapper) -> Unit,
) {
    val colors = XcsTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.textSecondary, fontSize = 12.sp)
    val tagStyle = TextStyle(color = colors.text, fontSize = 12.sp)
    val legStyle = TextStyle(color = colors.task, fontSize = 12.sp)

    Box(modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(colors.panel, RoundedCornerShape(12.dp))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        if (!hasData) {
            Text("No data yet", color = colors.textSecondary, fontSize = 15.sp)
            return@Box
        }
        Canvas(Modifier.matchParentSize()) {
            val pad = 12.dp.toPx()
            val axisLeft = 44.dp.toPx()
            val axisBottom = 22.dp.toPx()
            val m = ChartMapper(axisLeft, pad, size.width - axisLeft - pad,
                                size.height - pad - axisBottom, xMax, yMin, yMax)

            // value grid, in the pilot's unit
            val userMin = min(unit.toUser(yMin), unit.toUser(yMax))
            val userMax = max(unit.toUser(yMin), unit.toUser(yMax))
            val step = ChartAxis.niceStep(userMax - userMin)
            var v = ceil(userMin / step) * step
            while (v <= userMax + 1e-9) {
                val y = m.y(unit.toSi(v))
                drawLine(colors.panelBorder, Offset(m.leftX, y), Offset(m.right, y), 1f)
                val text = ChartAxis.label(v, step)
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(m.leftX - layout.size.width - 6f,
                                                  y - layout.size.height / 2f))
                v += step
            }

            // time grid
            val tStep = ChartAxis.timeStep(xMax)
            // from the first step on: the value axis labels the corner
            var t = tStep
            drawLine(colors.panelBorder, Offset(m.leftX, m.topY), Offset(m.leftX, m.bottom), 1f)
            while (t <= xMax + 1e-9) {
                val x = m.x(t)
                drawLine(colors.panelBorder, Offset(x, m.topY), Offset(x, m.bottom), 1f)
                val layout = measurer.measure(Format.duration(t * 3600).text, labelStyle)
                // centred under its line, but inside the panel
                val left = (x - layout.size.width / 2f)
                    .coerceAtMost(size.width - layout.size.width - 4f)
                drawText(layout, topLeft = Offset(left, m.bottom + 4f))
                t += tStep
            }

            for (leg in legs) {
                val x = m.x(leg.t)
                if (x < m.leftX || x > m.right) continue
                drawLine(colors.task, Offset(x, m.topY), Offset(x, m.bottom), 2f)
                drawText(measurer.measure(leg.index.toString(), legStyle),
                         topLeft = Offset(x + 4f, m.topY))
            }

            // trends and reference lines span the chart, never beyond it
            clipRect(m.leftX, m.topY, m.right, m.bottom) { draw(m) }

            for ((text, y) in labels) {
                val layout = measurer.measure(text, tagStyle)
                drawText(layout, topLeft = Offset(m.leftX + 8f,
                                                  m.y(y) - layout.size.height - 2f))
            }
        }
    }
}
