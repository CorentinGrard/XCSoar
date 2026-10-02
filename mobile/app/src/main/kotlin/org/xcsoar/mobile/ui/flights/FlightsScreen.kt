// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@Composable
fun FlightsScreen(viewModel: FlightsViewModel, onShare: (FlightLog) -> Unit,
                  onBack: () -> Unit) {
    val flights by viewModel.flights.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    FlightsContent(flights, onShare, onBack)
}

/** The IGC files of past flights, newest first, each with Share. */
@Composable
fun FlightsContent(
    flights: List<FlightLog>?,
    onShare: (FlightLog) -> Unit,
    onBack: () -> Unit,
    timeZone: TimeZone = TimeZone.getDefault(),
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
            Button("Back", primary = false, onClick = onBack)
            Text("Flights", color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        if (flights != null && flights.isEmpty())
            Text("No flights yet. XCSoar records an IGC file from takeoff to landing.",
                 color = colors.textSecondary, fontSize = 16.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .apply { this.timeZone = timeZone }
        for (flight in flights.orEmpty()) {
            Row(Modifier
                    .fillMaxWidth()
                    .background(colors.panel, RoundedCornerShape(14.dp))
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(flight.name, color = colors.text, fontSize = 17.sp,
                         fontWeight = FontWeight.SemiBold, maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text("${format.format(Date(flight.modified))} · ${kilobytes(flight.size)}",
                         color = colors.textSecondary, fontSize = 14.sp)
                }
                Button("Share", primary = true) { onShare(flight) }
            }
        }
    }
}

private fun kilobytes(bytes: Long) = "${(bytes + 1023) / 1024} kB"

@Composable
private fun Button(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Box(
        Modifier
            .height(56.dp)
            .widthIn(min = 96.dp)
            .background(if (primary) colors.selected else Color.Transparent,
                        RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) colors.onSelected else colors.text,
             fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun FlightsPreview() {
    XcsTheme(dark = false) {
        FlightsContent(listOf(
            FlightLog("/x/2026-10-02-XCS-AAA-01.igc", "2026-10-02-XCS-AAA-01.igc",
                      1_791_000_000_000, 412_000),
        ), {}, {})
    }
}
