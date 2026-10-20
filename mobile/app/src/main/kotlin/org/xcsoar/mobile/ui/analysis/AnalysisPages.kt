// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.AirspaceCrossSection
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.ChartPoint
import org.xcsoar.mobile.core.LatLon
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.XY
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.setup.ValueRow
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/* the pages of XCSoar's analysis dialog that are not over time: the
   core gives upstream's numbers, these only draw them */

private fun List<XY>.points() = map { ChartPoint(it.x, it.y) }

/** "V km/h": an axis' name and the pilot's unit. */
private fun axisName(name: String, group: UnitGroup) = "$name ${Format.unit(group).name}"

/** The area between the height axis and a profile of [XY.x] by [XY.y]. */
private fun DrawScope.profile(m: ChartMapper, points: List<XY>, color: Color) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(m.x(0.0), m.y(points.first().y))
        for (p in points) lineTo(m.x(p.x), m.y(p.y))
        lineTo(m.x(0.0), m.y(points.last().y))
        close()
    }
    drawPath(path, color)
}

/** ThermalBandRenderer: climb rate by height above the terrain base. */
@Composable
internal fun ThermalBandPage(a: Analysis) {
    val colors = XcsTheme.colors
    val b = a.thermalBand
    val range = b.range
    val all = b.collection + b.current + b.riskMacCready
    val xMax = max(max(0.5, b.macCready), all.maxOfOrNull { it.x } ?: 0.0) * 1.1
    val xMin = min(0.0, all.minOfOrNull { it.x } ?: 0.0)

    XYChart("Thermal band: climb rate by height", hasData = range != null,
            xMin = xMin, xMax = xMax, yMin = range?.get(0) ?: 0.0, yMax = range?.get(1) ?: 0.0,
            xUnit = Format.unit(UnitGroup.VERTICAL_SPEED),
            yUnit = Format.unit(UnitGroup.ALTITUDE),
            xName = axisName("w", UnitGroup.VERTICAL_SPEED),
            yName = axisName("h AGL", UnitGroup.ALTITUDE),
            labels = b.riskMacCready.lastOrNull()?.let { listOf("MC" to ChartPoint(it.x, it.y)) }
                .orEmpty()) { m ->
        // all climbs, and the one being flown in front
        profile(m, b.collection, colors.textSecondary.copy(alpha = 0.35f))
        profile(m, b.current, colors.updraft.copy(alpha = 0.6f))
        b.workingBand?.forEach { level(m, it, colors.textSecondary) }
        line(m, b.riskMacCready.points(), colors.warning, dashed = true)
        b.height?.let { h ->
            line(m, listOf(ChartPoint(0.0, h), ChartPoint(b.macCready, h)), colors.text)
            drawCircle(colors.text, 7f, Offset(m.x(b.macCready), m.y(h)))
        }
    }
    if (range != null)
        Hint("Grey: the climbs so far by height; blue: this climb.  The dot is your " +
             "height at your MacCready; dashed red, the MacCready the risk factor allows; " +
             "grey lines, the working band.")
    else
        Hint("No climb recorded yet.")
    a.climb.average?.let { ValueRow("Average climb", Format.macCready(it)) }
}

/** VarioHistogramRenderer: how often each climb rate was flown. */
@Composable
internal fun VarioHistogramPage(a: Analysis) {
    val colors = XcsTheme.colors
    val h = a.varioHistogram
    val all = h.cruise + h.circling
    val yMin = min(all.minOfOrNull { it.x } ?: 0.0, h.cruiseSink) - 0.5
    val yMax = max(all.maxOfOrNull { it.x } ?: 0.0, h.macCready) + 0.5
    val xMax = (all.maxOfOrNull { it.y } ?: 0.0) * 1.2
    // the climb rate goes up the chart: x is how often
    fun List<XY>.sideways() = map { ChartPoint(it.y, it.x) }.sortedBy { it.y }

    XYChart("Vario histogram: how often each climb rate was flown",
            hasData = all.isNotEmpty(), xMin = 0.0, xMax = xMax, yMin = yMin, yMax = yMax,
            xUnit = null, yUnit = Format.unit(UnitGroup.VERTICAL_SPEED),
            xName = "", yName = axisName("w", UnitGroup.VERTICAL_SPEED),
            labels = listOf("MC" to ChartPoint(xMax, h.macCready),
                            "S cruise" to ChartPoint(xMax, h.cruiseSink))) { m ->
        line(m, h.circling.sideways(), colors.updraft)
        line(m, h.cruise.sideways(), colors.textSecondary)
        level(m, h.macCready, colors.warning)
        level(m, h.cruiseSink, colors.updraft)
    }
    if (all.isNotEmpty())
        Hint("Blue: circling; grey: cruise.  Dashed: your MacCready, and the sink at " +
             "the best glide speed.")
}

/** RenderWindChart(): the wind from the lowest climb base to the highest top. */
@Composable
internal fun WindPage(a: Analysis) {
    val colors = XcsTheme.colors
    val levels = a.wind.levels
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.text, fontSize = 12.sp)
    val xMax = max(levels.maxOfOrNull { it.speed } ?: 0.0, 10.0) * 1.1

    XYChart("Wind by height", hasData = levels.size >= 2, xMin = 0.0, xMax = xMax,
            yMin = levels.minOfOrNull { it.height } ?: 0.0,
            yMax = levels.maxOfOrNull { it.height } ?: 0.0,
            xUnit = Format.unit(UnitGroup.WIND_SPEED), yUnit = Format.unit(UnitGroup.ALTITUDE),
            xName = axisName("w", UnitGroup.WIND_SPEED),
            yName = axisName("h", UnitGroup.ALTITUDE)) { m ->
        line(m, levels.map { ChartPoint(it.speed, it.height) }, colors.text)
        // the way it blows, north up, as long as it is strong
        val middle = (m.xMin + m.xMax) / 2
        for (l in levels) {
            val center = Offset(m.x(middle), m.y(l.height))
            val length = (l.speed / xMax * 2 * 24.dp.toPx()).toFloat()
            rotate((l.bearing + 180).toFloat(), center) {
                drawLine(colors.updraft, center + Offset(0f, length / 2),
                         center - Offset(0f, length / 2), 3f)
                drawLine(colors.updraft, center - Offset(0f, length / 2),
                         center - Offset(8f, length / 2 - 10f), 3f)
                drawLine(colors.updraft, center - Offset(0f, length / 2),
                         center - Offset(-8f, length / 2 - 10f), 3f)
            }
            drawText(measurer.measure(Format.bearing(l.bearing).text + "°", labelStyle),
                     topLeft = center + Offset(32.dp.toPx(), -18f))
        }
    }
    if (levels.size >= 2)
        Hint("The line: wind speed by height.  Arrows: where it blows, north up.")
    else
        Hint("Needs climbs: XCSoar measures the wind while circling.")
}

/** RenderGlidePolar() and its caption. */
@Composable
internal fun PolarPage(a: Analysis) {
    val colors = XcsTheme.colors
    val p = a.polar
    val all = p.polar + p.dolphin + p.climbs
    val yMin = min(all.minOfOrNull { it.y } ?: 0.0, -0.5)
    val yMax = max(all.maxOfOrNull { it.y } ?: 0.0, p.macCready) + 0.2
    // labels where upstream puts them: a fraction of the speed range along
    val label = { points: List<XY>, f: Double ->
        val x = p.vMin + f * (p.vMax - p.vMin)
        points.zipWithNext().firstOrNull { (a, b) -> x in a.x..b.x }?.let { (a, b) ->
            ChartPoint(x, a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x))
        }
    }

    XYChart("Polar: sink rate by airspeed", hasData = p.isValid,
            xMin = p.vMin, xMax = p.vMax, yMin = yMin, yMax = yMax,
            xUnit = Format.unit(UnitGroup.HORIZONTAL_SPEED),
            yUnit = Format.unit(UnitGroup.VERTICAL_SPEED),
            xName = axisName("V", UnitGroup.HORIZONTAL_SPEED),
            yName = axisName("w", UnitGroup.VERTICAL_SPEED),
            labels = listOfNotNull(label(p.polar, 0.9)?.let { "Polar" to it },
                                   label(p.bestGlide, 0.8)?.let { "Best glide" to it },
                                   p.dolphin.getOrNull(p.dolphin.size * 4 / 5)?.let {
                                       "Dolphin" to ChartPoint(it.x, it.y)
                                   })) { m ->
        level(m, 0.0, colors.panelBorder)
        line(m, p.bestGlide.points(), colors.updraft, width = 2f, dashed = true)
        line(m, p.dolphin.points(), colors.warning, dashed = true)
        line(m, p.climbs.points(), colors.updraft)
        line(m, p.polar.points(), colors.text)
    }
    if (!p.isValid) {
        Hint("No polar: choose the plane in Aircraft & crew.")
        return
    }
    Hint("Dashed red: the speed to fly in sink and lift at MacCready " +
         "${Format.macCready(p.macCready).text}; blue: the climb rates flown.")
    val speed = { v: Double -> Format.speed(v).let { "${it.text} ${it.unit}" } }
    ValueRow("Best L/D", Format.Value(Format.glideRatio(p.bestLd).text,
                                      "at ${speed(p.vBestLd)}"))
    ValueRow("Min. sink", Format.macCready(p.minSink).let {
        Format.Value(it.text, "${it.unit} at ${speed(p.vMinSink)}")
    })
    MassRows(p.mass, p.wingLoading)
}

@Composable
private fun MassRows(mass: Double, wingLoading: Double?) {
    ValueRow("Mass", Format.mass(mass))
    wingLoading?.let { ValueRow("Wing loading", Format.wingLoading(it)) }
}

/** RenderMacCready() and its caption. */
@Composable
internal fun MacCreadyPage(a: Analysis) {
    val colors = XcsTheme.colors
    val mc = a.macCready
    val speeds = mc.speeds
    val near = speeds.getOrNull((speeds.size * 0.9).toInt())

    XYChart("Speeds by MacCready", hasData = mc.isValid,
            xMin = 0.0, xMax = speeds.lastOrNull()?.macCready ?: 0.0,
            yMin = 0.0, yMax = mc.vMax,
            xUnit = Format.unit(UnitGroup.VERTICAL_SPEED),
            yUnit = Format.unit(UnitGroup.HORIZONTAL_SPEED),
            xName = axisName("MC", UnitGroup.VERTICAL_SPEED),
            yName = axisName("V", UnitGroup.HORIZONTAL_SPEED),
            labels = near?.let {
                listOf("Vopt" to ChartPoint(it.macCready, it.vOpt),
                       "Vave" to ChartPoint(it.macCready, it.vAverage))
            }.orEmpty()) { m ->
        line(m, speeds.map { ChartPoint(it.macCready, it.vOpt) }, colors.text)
        line(m, speeds.map { ChartPoint(it.macCready, it.vAverage) }, colors.updraft,
             dashed = true)
        vertical(m, mc.macCready, colors.warning)
    }
    if (!mc.isValid) {
        Hint("No polar: choose the plane in Aircraft & crew.")
        return
    }
    Hint("Vopt: the speed to fly between climbs; Vave: the average cross-country " +
         "speed.  Dashed red: your MacCready.")
    ValueRow("Vopt", Format.speed(mc.vOpt))
    ValueRow("Vave", Format.taskSpeed(mc.vAverage))
    MassRows(mc.mass, mc.wingLoading)
}

/** RenderTemperatureChart() and its caption. */
@Composable
internal fun TemperaturePage(a: Analysis) {
    val colors = XcsTheme.colors
    val t = a.temperature
    val levels = t.levels
    val all = levels.flatMap { listOfNotNull(it.dry, it.air, it.dewPoint) }

    XYChart("Temperature trace by height", hasData = levels.size >= 2,
            xMin = (all.minOrNull() ?: 0.0) - 1, xMax = (all.maxOrNull() ?: 0.0) + 1,
            yMin = 0.0, yMax = levels.maxOfOrNull { it.height } ?: 0.0,
            xUnit = Format.unit(UnitGroup.TEMPERATURE), yUnit = Format.unit(UnitGroup.ALTITUDE),
            xName = axisName("T", UnitGroup.TEMPERATURE),
            yName = axisName("h", UnitGroup.ALTITUDE)) { m ->
        line(m, levels.map { ChartPoint(it.dry, it.height) }, colors.warning, dashed = true)
        line(m, levels.map { ChartPoint(it.air, it.height) }, colors.text)
        line(m, levels.filter { it.dewPoint != null }.map { ChartPoint(it.dewPoint!!, it.height) },
             colors.updraft, dashed = true)
    }
    if (levels.size < 2) {
        Hint("Needs an outside air temperature from a device (a vario or a probe); " +
             "the phone has none.")
        return
    }
    Hint("Black: air temperature; dashed red: the dry adiabat; dashed blue: dew point.")
    t.thermalHeight?.let { ValueRow("Thermal height", Format.altitude(it)) }
    t.cloudBase?.let { ValueRow("Cloud base", Format.altitude(it)) }
}

/** FlightStatisticsRenderer::RenderTask(), without the zones, and CaptionTask(). */
@Composable
internal fun TaskPage(a: Analysis) {
    val colors = XcsTheme.colors
    val task = a.task
    val points = task.points
    val trace = a.contest.trace
    val measurer = rememberTextMeasurer()
    val nameStyle = TextStyle(color = colors.text, fontSize = 12.sp)

    Box(Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .background(colors.panel, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Task: its points and the flight" },
        contentAlignment = Alignment.Center) {
        if (points.size < 2) {
            Text("No task", color = colors.textSecondary, fontSize = 15.sp)
            return@Box
        }
        Canvas(Modifier.matchParentSize().padding(24.dp)) {
            val at = flatProjection(points.map { it.location } + points.map { it.target } +
                                    trace)
            if (trace.size >= 2)
                drawPath(path(trace, at), colors.textSecondary, style = Stroke(2f))
            drawPath(path(points.map { it.target }, at), colors.task, style = Stroke(4f))
            for (p in points) {
                drawCircle(colors.task, 8f, at(p.location), style = Stroke(3f))
                drawText(measurer.measure(p.name, nameStyle),
                         topLeft = at(p.location) + Offset(10f, -10f))
            }
        }
    }
    if (points.size < 2) {
        Hint("Load or make a task in Task.")
        return
    }
    task.taskToGo?.let { ValueRow("Task to go", Format.duration(it)) }
    task.aatToGo?.let { ValueRow("AAT to go", Format.duration(it)) }
    task.distanceToGo?.let { ValueRow("Distance to go", Format.distance(it)) }
    task.targetSpeed?.let { ValueRow("Target speed", Format.taskSpeed(it)) }
}

/**
 * North up on a flat projection fitting [all] into the canvas, as the
 * contest page draws the flight.
 */
internal fun DrawScope.flatProjection(all: List<LatLon>): (LatLon) -> Offset {
    val minLat = all.minOf { it.latitude }
    val maxLat = all.maxOf { it.latitude }
    val minLon = all.minOf { it.longitude }
    val maxLon = all.maxOf { it.longitude }
    val cosLat = cos((minLat + maxLat) / 2 * PI / 180)
    val spanX = max((maxLon - minLon) * cosLat, 1e-6)
    val spanY = max(maxLat - minLat, 1e-6)
    val scale = min(size.width / spanX, size.height / spanY)
    val offsetX = (size.width - spanX * scale) / 2
    val offsetY = (size.height - spanY * scale) / 2
    return { p ->
        Offset((offsetX + (p.longitude - minLon) * cosLat * scale).toFloat(),
               (offsetY + (maxLat - p.latitude) * scale).toFloat())
    }
}

internal fun path(points: List<LatLon>, at: (LatLon) -> Offset) = Path().apply {
    moveTo(at(points[0]).x, at(points[0]).y)
    for (p in points.drop(1)) lineTo(at(p).x, at(p).y)
}

/** "#RRGGBB" as a colour; [fallback] if it is not one. */
private fun hexColor(text: String, fallback: Color): Color =
    text.removePrefix("#").toLongOrNull(16)?.takeIf { text.length == 7 }
        ?.let { Color(0xFF000000 or it) } ?: fallback

/**
 * Upstream's airspace analysis page (CrossSectionRenderer): 50 km ahead
 * along the track, the airspaces in their map colours, the terrain and
 * the glide at MacCready from the aircraft (left).
 */
@Composable
internal fun AirspacePage(xs: AirspaceCrossSection?) {
    val colors = XcsTheme.colors
    if (xs == null || xs.heights.size != 2) {
        Hint("Not moving: the cross-section follows the track.")
        return
    }
    val ground = Color(0xFFA1887F)
    val terrain = xs.terrain.withIndex()
        .mapNotNull { (i, h) -> h?.let { ChartPoint(xs.range * i / (xs.terrain.size - 1), it) } }

    XYChart("Airspace and terrain ahead along the track", hasData = true,
            xMin = 0.0, xMax = xs.range, yMin = xs.heights[0], yMax = xs.heights[1],
            xUnit = Format.unit(UnitGroup.DISTANCE), yUnit = Format.unit(UnitGroup.ALTITUDE),
            xName = axisName("D", UnitGroup.DISTANCE), yName = axisName("h", UnitGroup.ALTITUDE),
            labels = xs.airspaces.mapNotNull { a ->
                a.spans.firstOrNull()?.let { span ->
                    a.name to ChartPoint(span.getOrElse(1) { xs.range }, (a.base + a.top) / 2)
                }
            }) { m ->
        for (a in xs.airspaces) {
            val fill = hexColor(a.fill, colors.neutralSafe)
            val border = hexColor(a.border, colors.text)
            for (span in a.spans) {
                if (span.size != 2) continue
                val topLeft = Offset(m.x(span[0]), m.y(a.top))
                val size = androidx.compose.ui.geometry.Size(m.x(span[1]) - topLeft.x,
                                                             m.y(a.base) - topLeft.y)
                drawRect(fill.copy(alpha = 0.35f), topLeft, size)
                drawRect(border, topLeft, size, style = Stroke(1.5.dp.toPx()))
            }
        }
        area(m, terrain, ground)
        if (xs.glide.size == 2 && xs.glide.all { it.size == 2 })
            line(m, xs.glide.map { ChartPoint(it[0], it[1]) }, colors.neutralSafe)
        xs.altitude?.let {
            drawCircle(colors.text, 5.dp.toPx(), Offset(m.x(0.0), m.y(it)))
        }
    }
    if (xs.airspaces.isEmpty())
        Hint("No airspace on the next ${Format.distance(xs.range).let { "${it.text} ${it.unit}" }} " +
             "along the track.")
    else for (a in xs.airspaces) {
        val base = if (a.base <= 0) "GND" else Format.altitude(a.base).let { "${it.text} ${it.unit}" }
        val top = Format.altitude(a.top).let { "${it.text} ${it.unit}" }
        val from = Format.distance(a.spans.firstOrNull()?.firstOrNull() ?: 0.0)
        ValueRow(a.name, Format.Value("${a.airspaceClass} · $base–$top · ${from.text}",
                                      from.unit))
    }
    Hint("Blue: the glide at MacCready; brown: the terrain.")
}
