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
import org.xcsoar.mobile.ui.ActionButton
import org.xcsoar.mobile.ui.ScreenHeader
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

@Composable
fun FlightsScreen(viewModel: FlightsViewModel, onShare: (FlightLog) -> Unit,
                  onOpenUrl: (String) -> Unit, onBack: () -> Unit) {
    val flights by viewModel.flights.collectAsStateWithLifecycle()
    val weGlideReady by viewModel.weGlideReady.collectAsStateWithLifecycle()
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    FlightsContent(flights, onShare, onBack, weGlideReady = weGlideReady, uploads = uploads,
                   onUpload = viewModel::upload, onOpenUrl = onOpenUrl)
}

/**
 * The IGC files of past flights, newest first, each with Share and,
 * once WeGlide is set up, Upload.
 */
@Composable
fun FlightsContent(
    flights: List<FlightLog>?,
    onShare: (FlightLog) -> Unit,
    onBack: () -> Unit,
    timeZone: TimeZone = TimeZone.getDefault(),
    weGlideReady: Boolean = false,
    uploads: Map<String, Upload> = emptyMap(),
    onUpload: (FlightLog) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
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
        ScreenHeader("Flights", onBack)

        if (!weGlideReady && !flights.isNullOrEmpty())
            Text("To upload flights to WeGlide, set your pilot ID in " +
                     "Menu → Settings → Pilot & WeGlide.",
                 color = colors.textSecondary, fontSize = 15.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        if (flights != null && flights.isEmpty())
            Text("No flights yet. XCSoar records an IGC file from takeoff to landing.",
                 color = colors.textSecondary, fontSize = 16.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .apply { this.timeZone = timeZone }
        for (flight in flights.orEmpty()) {
            val upload = uploads[flight.path]
            Column(Modifier
                       .fillMaxWidth()
                       .background(colors.panel, RoundedCornerShape(14.dp))
                       .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                   verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val info = @Composable { modifier: Modifier ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(flight.name, color = colors.text, fontSize = 17.sp,
                             fontWeight = FontWeight.SemiBold, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        Text("${format.format(Date(flight.modified))} · ${kilobytes(flight.size)}",
                             color = colors.textSecondary, fontSize = 14.sp)
                    }
                }
                val share = @Composable {
                    ActionButton("Share", primary = true) { onShare(flight) }
                }
                if (weGlideReady) {
                    // two buttons: below the name, which keeps the whole width
                    info(Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(
                            8.dp, Alignment.End)) {
                        if (upload !is Upload.Done)
                            ActionButton(if (upload == Upload.Running) "Uploading…"
                                         else "Upload to WeGlide",
                                         outlined = true, enabled = upload != Upload.Running) {
                                onUpload(flight)
                            }
                        share()
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        info(Modifier.weight(1f))
                        share()
                    }
                }
                when (upload) {
                    is Upload.Done -> Text(
                        "On WeGlide: flight ${upload.flight.flightId} · Open",
                        color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(role = Role.Button) { onOpenUrl(upload.flight.url) }
                            .padding(vertical = 8.dp))
                    is Upload.Failed -> Text("WeGlide: ${upload.message}",
                                             color = colors.warning, fontSize = 15.sp)
                    else -> {}
                }
            }
        }
    }
}

private fun kilobytes(bytes: Long) = "${(bytes + 1023) / 1024} kB"

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
