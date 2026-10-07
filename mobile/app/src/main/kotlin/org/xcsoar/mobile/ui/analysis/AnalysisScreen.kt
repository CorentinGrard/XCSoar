// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.analysis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.ContestInfo
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.setup.ValueRow
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The pages of XCSoar's analysis dialog this app has; [core] is the
 * name `xcs_tiles_click` gives them.
 */
enum class AnalysisPage(val label: String, val core: String) {
    BAROGRAPH("Barograph", "barograph"),
    CLIMB("Climb", "climb"),
    THERMAL_BAND("Thermal band", "thermal_band"),
    VARIO_HISTOGRAM("Vario", "vario_histogram"),
    TASK_SPEED("Speed", "task_speed"),
    WIND("Wind", "wind"),
    POLAR("Polar", "polar"),
    MAC_CREADY("MacCready", "maccready"),
    TEMPERATURE("Temperature", "temptrace"),
    TASK("Task", "task"),
    CONTEST("Contest", "contest");

    companion object {
        /** The page a tapped tile asks for; null if the app has none. */
        fun fromCore(name: String): AnalysisPage? = entries.firstOrNull { it.core == name }
    }
}

@Composable
fun AnalysisScreen(viewModel: AnalysisViewModel, onBack: () -> Unit) {
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshWhileShown() }
    BackHandler(onBack = onBack)
    AnalysisContent(analysis, page, viewModel::show, onBack)
}

/** XCSoar's analysis pages: a chart and the numbers upstream shows beside it. */
@Composable
fun AnalysisContent(
    analysis: Analysis?,
    page: AnalysisPage,
    onPage: (AnalysisPage) -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier
                    .height(56.dp)
                    .widthIn(min = 96.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center) {
                Text("Back", color = colors.text, fontSize = 16.sp,
                     fontWeight = FontWeight.SemiBold)
            }
            Text("Analysis", color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        PageTabs(page, onPage)

        val a = analysis ?: Analysis()
        when (page) {
            AnalysisPage.BAROGRAPH -> BarographPage(a)
            AnalysisPage.CLIMB -> ClimbPage(a)
            AnalysisPage.THERMAL_BAND -> ThermalBandPage(a)
            AnalysisPage.VARIO_HISTOGRAM -> VarioHistogramPage(a)
            AnalysisPage.TASK_SPEED -> TaskSpeedPage(a)
            AnalysisPage.WIND -> WindPage(a)
            AnalysisPage.POLAR -> PolarPage(a)
            AnalysisPage.MAC_CREADY -> MacCreadyPage(a)
            AnalysisPage.TEMPERATURE -> TemperaturePage(a)
            AnalysisPage.TASK -> TaskPage(a)
            AnalysisPage.CONTEST -> ContestPage(a.contest)
        }
    }
}

/** The chart's time span: the flight so far, at least 15 minutes. */
private fun timeSpan(a: Analysis, lastT: Double?) =
    max(max(a.flightTime ?: 0.0, lastT ?: 0.0), 0.25)

/**
 * The pages, one tab each, in upstream's order; the row scrolls and
 * keeps the page shown in view.
 */
@Composable
private fun PageTabs(page: AnalysisPage, onPage: (AnalysisPage) -> Unit) {
    val colors = XcsTheme.colors
    val scroll = rememberScrollState()
    Row(Modifier
            .fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(12.dp))
            .horizontalScroll(scroll)
            .selectableGroup()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in AnalysisPage.entries) {
            val selected = p == page
            Box(Modifier
                    .height(48.dp)
                    .background(if (selected) colors.selected else Color.Transparent,
                                RoundedCornerShape(9.dp))
                    .selectable(selected, role = Role.Tab) { onPage(p) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center) {
                Text(p.label, color = if (selected) colors.onSelected else colors.text,
                     fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
    // a tile may open a page further along the row
    LaunchedEffect(page) {
        scroll.animateScrollTo(scroll.maxValue * page.ordinal / (AnalysisPage.entries.size - 1))
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = XcsTheme.colors.textSecondary, fontSize = 15.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun BarographPage(a: Analysis) {
    val colors = XcsTheme.colors
    val b = a.barograph
    val altitude = b.altitude
    val terrain = b.terrain
    val all = altitude + terrain + b.ceiling + b.base
    val yMin = min(0.0, all.minOfOrNull { it.y } ?: 0.0)
    val yMax = max(all.maxOfOrNull { it.y } ?: 0.0, yMin + 100) * 1.05

    TimeChart("Barograph: altitude over the flight", hasData = altitude.size >= 2,
              xMax = timeSpan(a, altitude.lastOrNull()?.t), yMin = yMin, yMax = yMax,
              unit = Format.unit(UnitGroup.ALTITUDE), legs = a.legs) { m ->
        area(m, terrain, colors.textSecondary.copy(alpha = 0.25f))
        if (b.base.isNotEmpty()) line(m, b.base, colors.textSecondary, 2f, dashed = true)
        b.baseTrend?.let { trend(m, it, colors.textSecondary) }
        if (b.ceiling.isNotEmpty()) line(m, b.ceiling, colors.updraft, 2f, dashed = true)
        b.ceilingTrend?.let { trend(m, it, colors.updraft) }
        line(m, altitude, colors.text)
    }
    if (altitude.size >= 2) Hint("Dashed: climb tops in blue, climb bases in grey." +
         if (terrain.isNotEmpty()) " Shaded: terrain." else "")

    b.workingBand?.let { (low, high) ->
        val unit = Format.altitude(low).unit
        ValueRow("Working band",
                 Format.Value("${Format.altitude(low).text}–${Format.altitude(high).text}", unit))
    }
    b.ceilingGradient?.let {
        val v = Format.altitudeDifference(it)
        ValueRow("Ceiling trend", Format.Value(v.text, "${v.unit}/h"))
    }
}

@Composable
private fun ClimbPage(a: Analysis) {
    val colors = XcsTheme.colors
    val c = a.climb
    val thermals = c.thermals
    val yMin = min(0.0, thermals.minOfOrNull { it.lift } ?: 0.0)
    val yMax = max(thermals.maxOfOrNull { it.lift } ?: 0.0, c.macCready + 0.5) * 1.05
    val mc = Format.macCready(c.macCready)

    TimeChart("Climb history: the average of each climb", hasData = thermals.isNotEmpty(),
              xMax = timeSpan(a, thermals.maxOfOrNull { it.t + it.duration }),
              yMin = yMin, yMax = yMax, unit = Format.unit(UnitGroup.VERTICAL_SPEED),
              legs = a.legs, labels = listOf("MC" to c.macCready)) { m ->
        for (bar in thermals) {
            val left = m.x(bar.t)
            val right = max(m.x(bar.t + bar.duration), left + 3f)
            val top = min(m.y(bar.lift), m.y(0.0))
            drawRect(colors.updraft, Offset(left, top),
                     Size(right - left, kotlin.math.abs(m.y(bar.lift) - m.y(0.0))))
        }
        c.trend?.let { trend(m, it, colors.textSecondary) }
        level(m, c.macCready, colors.text)
    }
    if (thermals.isNotEmpty())
        Hint("Each bar is one climb, as wide as it lasted. Dashed: MacCready " +
             "${mc.text} ${mc.unit} and the climb trend.")

    c.average?.let { ValueRow("Average climb", Format.macCready(it)) }
    c.gradient?.let {
        val v = Format.vario(it)
        ValueRow("Climb trend", Format.Value(v.text, "${v.unit}/h"))
    }
}

@Composable
private fun TaskSpeedPage(a: Analysis) {
    val colors = XcsTheme.colors
    val s = a.taskSpeed
    val speeds = s?.speeds.orEmpty()
    val yMax = max(speeds.maxOfOrNull { it.y } ?: 0.0, s?.estimated ?: 0.0) * 1.1 + 1

    TimeChart("Task speed over the flight", hasData = s != null && speeds.size >= 2,
              xMax = timeSpan(a, speeds.lastOrNull()?.t), yMin = 0.0, yMax = yMax,
              unit = Format.unit(UnitGroup.TASK_SPEED), legs = a.legs,
              labels = s?.let { listOf("Vest" to it.estimated) }.orEmpty()) { m ->
        s ?: return@TimeChart
        level(m, s.estimated, colors.textSecondary)
        s.trend?.let { trend(m, it, colors.updraft) }
        line(m, speeds, colors.text)
    }
    if (s == null) {
        Hint("Needs a task: the chart starts once the task has started.")
    } else {
        Hint("Dashed: the trend in blue, and the speed the polar gives at the current " +
             "MacCready (Vest) in grey.")
        ValueRow("Average task speed", Format.taskSpeed(s.average))
        ValueRow("Estimated (Vest)", Format.taskSpeed(s.estimated))
    }
}

@Composable
private fun ContestPage(contest: ContestInfo) {
    if (contest.name.isNotEmpty())
        Caption(contest.name, Modifier.padding(start = 4.dp))

    ContestMap(contest)

    val results = contest.results.filter { it.isDefined }
    if (results.isEmpty()) {
        Hint("No contest result yet.")
        return
    }
    for (r in results) {
        if (r.label.isNotEmpty())
            Caption(r.label, Modifier.padding(start = 4.dp))
        ValueRow("Distance", Format.distance(r.distance))
        ValueRow("Score", Format.Value(String.format(Locale.ROOT, "%.1f", r.score), "pts"))
        ValueRow("Time", Format.duration(r.time))
        ValueRow("Speed", Format.taskSpeed(r.speed))
    }
}

/** The flight and the best contest path, north up, on a flat projection. */
@Composable
private fun ContestMap(contest: ContestInfo) {
    val colors = XcsTheme.colors
    val trace = contest.trace
    val best = contest.results.filter { it.isDefined }.maxByOrNull { it.score }?.points.orEmpty()
    val all = trace + best

    Box(Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .background(colors.panel, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Contest: the flight and the scored path" },
        contentAlignment = Alignment.Center) {
        if (all.size < 2) {
            Text("No data yet", color = colors.textSecondary, fontSize = 15.sp)
            return@Box
        }
        Canvas(Modifier.matchParentSize().padding(16.dp)) {
            val at = flatProjection(all)
            if (trace.size >= 2)
                drawPath(path(trace, at), colors.textSecondary, style = Stroke(2f))
            if (best.size >= 2) {
                drawPath(path(best, at), colors.route, style = Stroke(5f))
                for (p in best)
                    drawCircle(colors.route, radius = 7f, center = at(p))
            }
        }
    }
}
