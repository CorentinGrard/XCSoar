// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun FlightScreen(viewModel: FlightViewModel, onOpenDataFiles: () -> Unit = {}) {
    val state by viewModel.flightState.collectAsStateWithLifecycle()
    val lastEvent by viewModel.lastEvent.collectAsStateWithLifecycle()
    val circling by viewModel.showCircling.collectAsStateWithLifecycle()
    val mapFollows by viewModel.mapFollows.collectAsStateWithLifecycle()

    FlightContent(
        state = state,
        lastEvent = lastEvent,
        circling = circling,
        onMacCreadyChange = viewModel::changeMacCready,
        onSelectMode = viewModel::selectFlightMode,
        menu = listOf(
            MenuAction("Data files", enabled = true, onClick = onOpenDataFiles),
            // on the ground only: a demo, not something to press in flight
            MenuAction("Replay demo",
                       enabled = state?.let { !it.flying && !it.replay } == true,
                       onClick = viewModel::replayDemo),
            MenuAction("Stop replay", enabled = state?.replay == true,
                       onClick = viewModel::stopReplay),
        ),
        map = if (viewModel.hasMap) MapSlot(
            content = { modifier ->
                CoreMapView(viewModel::attachMap, viewModel::detachMap, modifier)
            },
            onAircraftPosition = viewModel::setMapAircraftPosition,
            onZoom = viewModel::zoomMap,
            onGesture = viewModel::mapGesture,
            follows = mapFollows,
            onFollow = viewModel::followMap,
        ) else null,
    )
}

/**
 * XCSoar's moving map, when the core draws one.
 *
 * @param onAircraftPosition where to draw the aircraft, in pixels: the
 * middle of the part of the map the cards leave free
 * @param onZoom steps of XCSoar's scale list (negative = in)
 * @param onGesture finger pan in pixels and pinch factor (> 1 = in)
 * @param follows whether the map follows the aircraft
 * @param onFollow centre on the aircraft again
 */
class MapSlot(
    val content: @Composable (Modifier) -> Unit,
    val onAircraftPosition: (x: Int, y: Int) -> Unit,
    val onZoom: (steps: Int) -> Unit,
    val onGesture: (dx: Float, dy: Float, zoom: Float) -> Unit = { _, _, _ -> },
    val follows: Boolean = true,
    val onFollow: () -> Unit = {},
)

/**
 * The flight screen: map with floating cards, and the instruments in a
 * bottom sheet (portrait) or a side panel (landscape).  Without
 * [map] (no native core) the map area only shows the glider symbol.
 */
@Composable
fun FlightContent(
    state: FlightState?,
    lastEvent: String?,
    circling: Boolean,
    onMacCreadyChange: (Double) -> Unit,
    onSelectMode: (circling: Boolean) -> Unit = {},
    menu: List<MenuAction> = emptyList(),
    map: MapSlot? = null,
) {
    val colors = XcsTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val instruments = @Composable {
            Instruments(state, circling, onMacCreadyChange, onSelectMode, menu)
        }

        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                MapArea(state, lastEvent, map, Modifier.weight(1f).fillMaxHeight(),
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical +
                                                      WindowInsetsSides.Start))
                Column(
                    Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                        .background(colors.sheet)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Vertical + WindowInsetsSides.End))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { instruments() }
            }
        } else {
            // the sheet may scroll on short screens; the next waypoint
            // card above it always stays visible
            val sheetMax = maxHeight - 160.dp
            Column(Modifier.fillMaxSize()) {
                MapArea(state, lastEvent, map, Modifier.weight(1f).fillMaxWidth(),
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top +
                                                      WindowInsetsSides.Horizontal))
                val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetMax)
                        .shadow(16.dp, sheetShape, ambientColor = colors.text,
                                spotColor = colors.text)
                        .background(colors.sheet, sheetShape)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp).height(4.dp)
                        .background(colors.panelBorder, RoundedCornerShape(2.dp)))
                    instruments()
                }
            }
        }
    }
}

@Composable
private fun MapArea(
    state: FlightState?,
    lastEvent: String?,
    map: MapSlot?,
    modifier: Modifier,
    insets: WindowInsets,
) {
    val colors = XcsTheme.colors
    // the glider goes in the middle of the map area the cards leave free
    var cardsBottom by remember { mutableIntStateOf(0) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    if (map != null)
        LaunchedEffect(cardsBottom, mapSize) {
            if (mapSize != IntSize.Zero)
                map.onAircraftPosition(mapSize.width / 2,
                                       (cardsBottom.coerceAtMost(mapSize.height) +
                                        mapSize.height) / 2)
        }

    Box(modifier.onSizeChanged { mapSize = it }) {
        if (map != null) {
            map.content(Modifier.fillMaxSize())
            // pan and pinch; Compose waits for the touch slop, so a tap
            // is never a drag (doc/architecture.rst, "Touch interaction")
            // a recomposition (e.g. "follows" turning false) must not
            // restart the gesture under the finger
            val onGesture by rememberUpdatedState(map.onGesture)
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onGesture(pan.x, pan.y, zoom)
                }
            })
        } else
            MapPlaceholder(state?.track, cardsBottom, Modifier.fillMaxSize())

        Column(
            Modifier
                .onSizeChanged { cardsBottom = it.height }
                .windowInsetsPadding(insets)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NextWaypointCard(state?.next, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state == null -> StatusChip("Starting…", colors.textSecondary)
                    else -> {
                        WindChip(state.wind)
                        if (state.position == null) StatusChip("NO GPS", colors.caution)
                        if (state.replay) StatusChip("REPLAY", colors.neutralSafe)
                        if (!state.flying) StatusChip("On ground", colors.text)
                    }
                }
                if (lastEvent != null) StatusChip(lastEvent, colors.textSecondary)
            }
        }

        if (map != null)
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(insets)
                    .padding(12.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!map.follows)
                    CentreButton(map.onFollow)
                ZoomButtons(map.onZoom)
            }
    }
}

@Composable
private fun Instruments(
    state: FlightState?,
    circling: Boolean,
    onMacCreadyChange: (Double) -> Unit,
    onSelectMode: (circling: Boolean) -> Unit,
    menu: List<MenuAction>,
) {
    VarioPanel(state?.vario, state?.averageVario, state?.nettoVario, Modifier.fillMaxWidth())

    infoBoxes(state, circling).chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { box ->
                InfoBox(box.title, box.value, Modifier.weight(1f), box.color)
            }
        }
    }

    // both tiles as tall as the taller one
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MacCreadyControl(state?.macCready, onMacCreadyChange,
                         Modifier.weight(1f).fillMaxHeight())
        FinalGlideTile(state?.finalGlide, Modifier.weight(1f).fillMaxHeight())
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FlightModeSwitch(circling, onSelectMode, Modifier.weight(1f))
        FlightMenuButton(menu)
    }
}

private class InfoBoxValue(val title: String, val value: Format.Value,
                           val color: Color? = null)

/** Six InfoBoxes for the current layout; configurable later (M6). */
@Composable
private fun infoBoxes(s: FlightState?, circling: Boolean): List<InfoBoxValue> {
    val colors = XcsTheme.colors
    val altitude = InfoBoxValue("Altitude", Format.altitude(s?.navAltitude))
    val agl = InfoBoxValue("AGL", Format.altitude(s?.altitudeAgl))
    val flightTime = InfoBoxValue("Flight time",
                          Format.duration(s?.flightTime?.takeIf { s.flying }))
    return if (circling) {
        val average = s?.averageVario
        listOf(
            InfoBoxValue("Avg 30 s", Format.vario(average), when {
                average == null -> null
                average >= 0.05 -> colors.lift
                average <= -0.05 -> colors.sink
                else -> null
            }),
            altitude,
            agl,
            InfoBoxValue("Airspeed", Format.speed(s?.indicatedAirspeed)),
            InfoBoxValue("Wind", Format.speed(s?.wind?.speed)),
            flightTime,
        )
    } else {
        listOf(
            altitude,
            agl,
            InfoBoxValue("Ground speed", Format.speed(s?.groundSpeed)),
            InfoBoxValue("Airspeed", Format.speed(s?.indicatedAirspeed)),
            InfoBoxValue("Track", Format.bearing(s?.track)),
            flightTime,
        )
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun CruisePreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(120), "Cruise", circling = false, {})
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun CirclingPreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(320), "Climb", circling = true, {})
    }
}

@Preview(widthDp = 844, heightDp = 390)
@Composable
private fun LandscapePreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun NightNoDataPreview() {
    XcsTheme(dark = true) {
        FlightContent(null, null, circling = false, {})
    }
}
