// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun FlightScreen(viewModel: FlightViewModel) {
    val state by viewModel.flightState.collectAsStateWithLifecycle()
    val lastEvent by viewModel.lastEvent.collectAsStateWithLifecycle()

    FlightContent(
        state = state,
        lastEvent = lastEvent,
        onMacCreadyChange = viewModel::changeMacCready,
        onReplayDemo = viewModel::replayDemo,
    )
}

/**
 * The flight screen v0: status line, InfoBox grid and MacCready quick
 * control.  The map arrives in M3.
 */
@Composable
fun FlightContent(
    state: FlightState?,
    lastEvent: String?,
    onMacCreadyChange: (Double) -> Unit,
    onReplayDemo: () -> Unit = {},
) {
    val colors = XcsTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusLine(state, lastEvent, onReplayDemo)

        val boxes = infoBoxes(state)
        // 2 columns; rows share the remaining height
        boxes.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { box -> box(this) }
            }
        }

        MacCreadyBar(state?.macCready, onMacCreadyChange)
    }
}

private typealias BoxSlot = @Composable RowScope.() -> Unit

private fun infoBoxes(s: FlightState?): List<BoxSlot> {
    val vario = s?.vario
    return listOf(
        { InfoBox("Vario", Format.vario(vario), Modifier.weight(1f).fillMaxHeight(),
                  // no colour for values shown as 0.0: the bar must agree with the number
                  accent = vario?.let { when {
                      it >= 0.05 -> XcsTheme.colors.lift
                      it <= -0.05 -> XcsTheme.colors.sink
                      else -> null
                  } }) },
        { InfoBox("Vario 30 s", Format.vario(s?.averageVario), Modifier.weight(1f).fillMaxHeight()) },
        { InfoBox("Altitude", Format.altitude(s?.navAltitude), Modifier.weight(1f).fillMaxHeight()) },
        { InfoBox("Height AGL", Format.altitude(s?.altitudeAgl), Modifier.weight(1f).fillMaxHeight()) },
        { InfoBox("Ground speed", Format.speed(s?.groundSpeed), Modifier.weight(1f).fillMaxHeight()) },
        { InfoBox("Track", Format.bearing(s?.track), Modifier.weight(1f).fillMaxHeight()) },
        { InfoBox("Wind", Format.speed(s?.wind?.speed), Modifier.weight(1f).fillMaxHeight()) },
        { InfoBox("Wind from", Format.bearing(s?.wind?.bearing), Modifier.weight(1f).fillMaxHeight()) },
        { InfoBox("Next " + (s?.next?.name ?: ""), Format.distance(s?.next?.distance),
                  Modifier.weight(1f).fillMaxHeight(), accent = XcsTheme.colors.task) },
        { InfoBox("Final glide", Format.altitudeDifference(s?.finalGlide?.altitudeDifference),
                  Modifier.weight(1f).fillMaxHeight(),
                  accent = s?.finalGlide?.let {
                      if (it.altitudeDifference >= 0) XcsTheme.colors.safe else XcsTheme.colors.caution
                  }) },
    )
}

@Composable
private fun StatusLine(state: FlightState?, lastEvent: String?, onReplayDemo: () -> Unit) {
    val colors = XcsTheme.colors
    val mode = when {
        state == null -> "Starting…"
        !state.flying -> "On ground"
        state.circling -> "Circling"
        else -> "Cruise"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(mode, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (state?.replay == true)
            Text("  REPLAY", color = colors.neutralSafe, fontSize = 13.sp)
        if (state != null && state.position == null)
            Text("  NO GPS", color = colors.caution, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(lastEvent ?: "", color = colors.textSecondary, fontSize = 13.sp,
             modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
        // on the ground only: a demo, not something to press in flight
        if (state != null && !state.flying && !state.replay)
            TextButton(onClick = onReplayDemo) {
                Text("Replay demo", color = colors.neutralSafe, fontSize = 14.sp)
            }
    }
}

/**
 * MacCready quick control.  Buttons are at least 56 dp and act on
 * release (Compose buttons fire on lift-off; sliding off cancels), as
 * doc/architecture.rst "Touch interaction" requires.
 */
@Composable
private fun MacCreadyBar(macCready: Double?, onChange: (Double) -> Unit) {
    val colors = XcsTheme.colors
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = colors.panel, contentColor = colors.text)
    Row(Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onChange(-0.1) }, enabled = macCready != null,
               colors = buttonColors,
               modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 56.dp)) {
            Text("MC −", fontSize = 18.sp)
        }
        val value = macCready?.let { Format.macCready(it) } ?: Format.Value(Format.INVALID, "m/s")
        Text("MC ${value.text} ${value.unit}", color = colors.text, fontSize = 22.sp,
             fontWeight = FontWeight.Bold,
             modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Button(onClick = { onChange(+0.1) }, enabled = macCready != null,
               colors = buttonColors,
               modifier = Modifier.sizeIn(minWidth = 72.dp, minHeight = 56.dp)) {
            Text("MC +", fontSize = 18.sp)
        }
    }
}

@Preview(widthDp = 390, heightDp = 800)
@Composable
private fun FlightContentPreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(320), "Climb", {})
    }
}

@Preview(widthDp = 390, heightDp = 800)
@Composable
private fun FlightContentNoDataPreview() {
    XcsTheme(dark = true) {
        FlightContent(null, null, {})
    }
}
