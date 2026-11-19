// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.summary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ValueList
import org.xcsoar.mobile.ui.analysis.ContestMap
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flights.FlightLog
import org.xcsoar.mobile.ui.flights.FlightsViewModel
import org.xcsoar.mobile.ui.flights.Upload
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale

/**
 * After the landing: the flight at a glance, then Share and Upload to
 * WeGlide (as under Flights) for its IGC file.
 */
@Composable
fun SummaryScreen(
    viewModel: SummaryViewModel,
    flights: FlightsViewModel,
    onShare: (FlightLog) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uploads by flights.uploads.collectAsStateWithLifecycle()
    val weGlideReady by flights.weGlideReady.collectAsStateWithLifecycle()
    // WeGlide's settings and the uploads remembered
    LaunchedEffect(state.log) { flights.refresh() }
    BackHandler(onBack = onDone)
    SummaryContent(state, weGlideReady, state.log?.let { uploads[it.path] },
                   onShare = { state.log?.let(onShare) },
                   onUpload = { state.log?.let(flights::upload) },
                   onOpenUrl = onOpenUrl, onDone = onDone)
}

@Composable
fun SummaryContent(
    state: SummaryState,
    weGlideReady: Boolean,
    upload: Upload?,
    onShare: () -> Unit = {},
    onUpload: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onDone: () -> Unit = {},
) {
    val colors = XcsTheme.colors
    val summary = state.summary ?: return
    val log = state.log
    PageLayout(bottom = {
        if (log != null) {
            ActionButton("Share", outlined = true, onClick = onShare)
            if (weGlideReady && upload !is Upload.Done)
                ActionButton(if (upload == Upload.Running) "Uploading…" else "Upload",
                             outlined = true, enabled = upload != Upload.Running,
                             onClick = onUpload)
        }
        Spacer(Modifier.weight(1f))
        ActionButton("Done", primary = true, onClick = onDone)
    }) {
        val times = summary.times
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(when {
                     summary.replay -> "Replay finished"
                     times?.landingLocal.isNullOrEmpty() -> "Landed"
                     else -> "Landed at ${times?.landingLocal}"
                 },
                 color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(
                     times?.takeoffLocal?.ifEmpty { null }?.let { "Take-off $it" },
                     summary.contest.name.ifEmpty { null }).joinToString(" · "),
                 color = colors.textSecondary, fontSize = 16.sp)
        }

        // the three numbers of the flight
        val result = summary.result
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Time", Format.duration(summary.duration), Modifier.weight(1f))
            Stat("Distance", Format.distance(result?.distance), Modifier.weight(1f))
            Stat("Score", Format.Value(result?.let { String.format(Locale.ROOT, "%.0f", it.score) }
                                           ?: Format.INVALID, "pts"),
                 Modifier.weight(1f))
        }

        ContestMap(summary.contest)

        val best = summary.bestClimb
        val climb = Format.vario(best?.lift)
        ValueList(listOfNotNull(
            "Best climb" to (best?.let {
                "${climb.text} ${climb.unit} · ${Format.altitude(it.gain).text} " +
                    Format.altitude(it.gain).unit
            } ?: ""),
            "Average climb" to (summary.averageClimb?.let {
                val v = Format.vario(it)
                "${v.text} ${v.unit} · ${summary.climbs} climbs"
            } ?: ""),
            "Highest" to (summary.highest?.let {
                Format.altitude(it).let { v -> "${v.text} ${v.unit}" }
            } ?: ""),
            result?.label?.ifEmpty { null }?.let { "Contest" to it },
            result?.takeIf { it.speed > 0 }?.let {
                "Speed" to Format.taskSpeed(it.speed).let { v -> "${v.text} ${v.unit}" }
            },
        ))

        when {
            summary.replay ->
                Hint("A replay: nothing was recorded, nothing to share.")
            log == null && state.waitingForLog ->
                Hint("The flight log closes a little after the landing; Share and " +
                         "WeGlide follow.")
            log == null ->
                Hint("No flight log found: Menu → Flights has the recorded ones.")
            else -> {
                Text(log.name, color = colors.textSecondary, fontSize = 14.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.padding(horizontal = 4.dp))
                if (!weGlideReady)
                    Hint("To upload to WeGlide, set your pilot ID: Menu → Pilot & WeGlide.")
            }
        }
        when (upload) {
            is Upload.Done -> Text(
                "On WeGlide: flight ${upload.flight.flightId} · Open",
                color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onOpenUrl(upload.flight.url) }
                    .padding(horizontal = 4.dp, vertical = 12.dp))
            is Upload.Failed -> Text("WeGlide: ${upload.message}", color = colors.warning,
                                     fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
            else -> {}
        }
    }
}

/** One of the flight's three numbers, large. */
@Composable
private fun Stat(label: String, value: Format.Value, modifier: Modifier) {
    val colors = XcsTheme.colors
    Column(modifier
               .background(colors.panel, RoundedCornerShape(14.dp))
               .padding(horizontal = 12.dp, vertical = 10.dp)
               .clearAndSetSemantics { contentDescription = "$label ${value.text} ${value.unit}" },
           verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Caption(label)
        Row {
            Text(value.text, color = colors.text, style = XcsTheme.numberStyle,
                 fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (value.unit.isNotEmpty())
                Text(" ${value.unit}", color = colors.textSecondary, style = XcsTheme.numberStyle,
                     fontSize = 15.sp, maxLines = 1,
                     modifier = Modifier.padding(top = 9.dp))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = XcsTheme.colors.textSecondary, fontSize = 15.sp,
         modifier = Modifier.padding(horizontal = 4.dp))
}
