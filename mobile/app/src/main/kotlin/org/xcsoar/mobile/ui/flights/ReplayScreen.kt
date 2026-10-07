// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.SettingsGroup
import org.xcsoar.mobile.ui.flight.ReplaySpeed
import org.xcsoar.mobile.ui.flight.Segmented
import org.xcsoar.mobile.ui.flight.speedText
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@Composable
fun ReplayScreen(
    flights: FlightsViewModel,
    speed: ReplaySpeed,
    onSpeed: (Double) -> Unit,
    onDemo: () -> Unit,
    onReplay: (FlightLog) -> Unit,
    onOpenFile: () -> Unit,
    onBack: () -> Unit,
) {
    val list by flights.flights.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { flights.refresh() }
    BackHandler(onBack = onBack)
    ReplayContent(list, speed, onSpeed, onDemo, onReplay, onOpenFile, onBack)
}

/**
 * Pick a flight to replay through XCSoar, and how fast: the demo
 * flight, the recorded ones (newest first) or any IGC or NMEA file.
 */
@Composable
fun ReplayContent(
    flights: List<FlightLog>?,
    speed: ReplaySpeed,
    onSpeed: (Double) -> Unit,
    onDemo: () -> Unit,
    onReplay: (FlightLog) -> Unit,
    onOpenFile: () -> Unit,
    onBack: () -> Unit,
    timeZone: TimeZone = TimeZone.getDefault(),
) {
    val colors = XcsTheme.colors
    PageLayout {
        ScreenHeader("Replay", onBack)
        Text("The flight goes through XCSoar as if flown: map, tiles, airspace warnings " +
                 "and vario sound.  The GPS is left aside until the replay stops; nothing " +
                 "is recorded.",
             color = colors.textSecondary, fontSize = 15.sp,
             modifier = Modifier.padding(horizontal = 4.dp))

        Text("Speed", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        Segmented(ReplaySpeed.CHOICES.map { "${speedText(it)}×" },
                  ReplaySpeed.CHOICES.indexOf(speed.timeScale),
                  { onSpeed(ReplaySpeed.CHOICES[it]) }, Modifier.fillMaxWidth())

        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .apply { this.timeZone = timeZone }
        SettingsGroup {
            FlightRow("Demo flight", "A cross-country flight in Australia", onDemo)
            for (flight in flights.orEmpty()) {
                HorizontalDivider(color = colors.panelBorder)
                FlightRow(flight.name, format.format(Date(flight.modified))) { onReplay(flight) }
            }
        }
        ActionButton("Open a file…", outlined = true, onClick = onOpenFile)
    }
}

@Composable
private fun FlightRow(title: String, detail: String, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Column(Modifier
               .fillMaxWidth()
               .heightIn(min = 56.dp)
               .clickable(role = Role.Button, onClickLabel = "Replay $title", onClick = onClick)
               .padding(horizontal = 14.dp, vertical = 8.dp),
           verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = colors.textSecondary, fontSize = 14.sp)
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ReplayPreview() {
    XcsTheme(dark = false) {
        ReplayContent(listOf(
            FlightLog("/x/2026-10-02-XCS-AAA-01.igc", "2026-10-02-XCS-AAA-01.igc",
                      1_791_000_000_000, 412_000),
        ), ReplaySpeed(), {}, {}, {}, {}, {})
    }
}
