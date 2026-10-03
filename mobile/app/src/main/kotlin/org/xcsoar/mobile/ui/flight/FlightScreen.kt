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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.core.TileValue
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsColors
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun FlightScreen(
    viewModel: FlightViewModel,
    onOpenDataFiles: () -> Unit = {},
    onOpenMapSettings: () -> Unit = {},
    onOpenWaypoints: () -> Unit = {},
    onOpenFlightSetup: () -> Unit = {},
    onOpenFlights: () -> Unit = {},
    onOpenTask: () -> Unit = {},
    onOpenUnits: () -> Unit = {},
    onOpenAnalysis: () -> Unit = {},
    onOpenCrew: () -> Unit = {},
    onOpenPilot: () -> Unit = {},
    onEditTile: (TileLayout, Int) -> Unit = { _, _ -> },
) {
    val state by viewModel.flightState.collectAsStateWithLifecycle()
    val lastEvent by viewModel.lastEvent.collectAsStateWithLifecycle()
    val circling by viewModel.showCircling.collectAsStateWithLifecycle()
    val mapFollows by viewModel.mapFollows.collectAsStateWithLifecycle()
    val mapOrientation by viewModel.mapOrientation.collectAsStateWithLifecycle()
    val mapItems by viewModel.mapItems.collectAsStateWithLifecycle()
    val warnings by viewModel.airspaceWarnings.collectAsStateWithLifecycle()
    val varioSound by viewModel.varioSound.collectAsStateWithLifecycle()
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()

    FlightContent(
        state = state,
        lastEvent = lastEvent,
        circling = circling,
        tiles = tiles,
        onEditTile = { tile ->
            onEditTile(if (circling) TileLayout.CIRCLING else TileLayout.CRUISE, tile)
        },
        onMacCreadyChange = viewModel::setMacCready,
        onSetMacCready = viewModel::setMacCready,
        onSelectMode = viewModel::selectFlightMode,
        warnings = warnings,
        onAcknowledge = viewModel::acknowledgeAirspace,
        onNextWaypoint = onOpenWaypoints,
        varioSound = varioSound,
        onVarioSound = viewModel::setVarioSound,
        menu = listOf(
            MenuAction("Go to waypoint", enabled = true, onClick = onOpenWaypoints),
            MenuAction("Task", enabled = true, onClick = onOpenTask),
            MenuAction("Aircraft & crew", enabled = true, onClick = onOpenCrew),
            MenuAction("Flight setup", enabled = true, onClick = onOpenFlightSetup),
            MenuAction("Analysis", enabled = true, onClick = onOpenAnalysis),
            MenuAction("Flights", enabled = true, onClick = onOpenFlights),
            MenuAction("Pilot & WeGlide", enabled = true, onClick = onOpenPilot),
            MenuAction("Units", enabled = true, onClick = onOpenUnits),
            MenuAction("Data files", enabled = true, onClick = onOpenDataFiles),
            MenuAction("Map", enabled = viewModel.hasMap, onClick = onOpenMapSettings),
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
            orientation = mapOrientation,
            onOrientation = viewModel::cycleMapOrientation,
            onHold = viewModel::showMapItems,
            items = mapItems,
            onCloseItems = viewModel::hideMapItems,
            onGoto = viewModel::gotoWaypoint,
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
 * @param orientation which way is up; null hides the button
 * @param onOrientation switch to the next orientation
 * @param onHold the pilot held this point (pixels): show what is there
 * @param items what is at the held point; null when not shown
 * @param onGoto fly directly to a waypoint of [items]
 */
class MapSlot(
    val content: @Composable (Modifier) -> Unit,
    val onAircraftPosition: (x: Int, y: Int) -> Unit,
    val onZoom: (steps: Int) -> Unit,
    val onGesture: (dx: Float, dy: Float, zoom: Float) -> Unit = { _, _, _ -> },
    val follows: Boolean = true,
    val onFollow: () -> Unit = {},
    val orientation: MapOrientation? = null,
    val onOrientation: () -> Unit = {},
    val onHold: (x: Int, y: Int) -> Unit = { _, _ -> },
    val items: List<MapItemInfo>? = null,
    val onCloseItems: () -> Unit = {},
    val onGoto: (waypointId: Int) -> Unit = {},
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
    onSetMacCready: (Double) -> Unit = {},
    onSelectMode: (circling: Boolean) -> Unit = {},
    menu: List<MenuAction> = emptyList(),
    map: MapSlot? = null,
    warnings: List<AirspaceWarningInfo> = emptyList(),
    onAcknowledge: (AirspaceWarningInfo, day: Boolean) -> Unit = { _, _ -> },
    onNextWaypoint: () -> Unit = {},
    varioSound: Boolean? = null,
    onVarioSound: (Boolean) -> Unit = {},
    tiles: List<TileValue>? = null,
    onEditTile: (Int) -> Unit = {},
) {
    val colors = XcsTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val instruments = @Composable {
            Instruments(state, circling, tiles, onEditTile, onMacCreadyChange, onSetMacCready,
                        onSelectMode, menu)
        }

        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                MapArea(state, lastEvent, map, warnings, onAcknowledge, onNextWaypoint,
                        varioSound, onVarioSound,
                        Modifier.weight(1f).fillMaxHeight(),
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
                MapArea(state, lastEvent, map, warnings, onAcknowledge, onNextWaypoint,
                        varioSound, onVarioSound,
                        Modifier.weight(1f).fillMaxWidth(),
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
    warnings: List<AirspaceWarningInfo>,
    onAcknowledge: (AirspaceWarningInfo, day: Boolean) -> Unit,
    onNextWaypoint: () -> Unit,
    /** null: no vario sound on this device, no button */
    varioSound: Boolean?,
    onVarioSound: (Boolean) -> Unit,
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
            val onHold by rememberUpdatedState(map.onHold)
            var holdPoint by remember { mutableStateOf<Offset?>(null) }
            Box(Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onGesture(pan.x, pan.y, zoom)
                    }
                }
                .pointerInput(Unit) {
                    detectHoldRelease(
                        onArmed = { holdPoint = it },
                        onRelease = { p, commit ->
                            holdPoint = null
                            if (commit) onHold(p.x.toInt(), p.y.toInt())
                        })
                })
            holdPoint?.let { HoldMarker(it) }
        } else
            MapPlaceholder(state?.track, cardsBottom, Modifier.fillMaxSize())

        Column(
            Modifier
                .onSizeChanged { cardsBottom = it.height }
                .windowInsetsPadding(insets)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            warnings.firstOrNull()?.let { top ->
                AirspaceWarningBanner(top, warnings.size - 1, { day -> onAcknowledge(top, day) },
                                      Modifier.fillMaxWidth())
            }
            NextWaypointCard(state?.next, Modifier.fillMaxWidth(), state?.nextTimeRemaining,
                             onClick = onNextWaypoint)
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

        map?.items?.let {
            MapItemsCard(it, map.onCloseItems, map.onGoto, Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(insets)
                .padding(start = 12.dp, end = 84.dp, bottom = 12.dp))
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(insets)
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            varioSound?.let { VarioSoundButton(it, onVarioSound) }
            if (map != null) {
                map.orientation?.let {
                    OrientationButton(it, mapAngle(it, state), map.onOrientation)
                }
                if (!map.follows)
                    CentreButton(map.onFollow)
                ZoomButtons(map.onZoom)
            }
        }
    }
}

/**
 * The direction at the top of the map, for the orientation button's
 * north mark: the same references the core turns the map with
 * (core/map/CoreMap.cpp ScreenAngle()); null when the core has none.
 */
private fun mapAngle(orientation: MapOrientation, s: FlightState?): Double? = when (orientation) {
    MapOrientation.NORTH_UP -> 0.0
    MapOrientation.TRACK_UP -> s?.track
    MapOrientation.TARGET_UP -> s?.next?.bearing ?: s?.track
    MapOrientation.WIND_UP -> s?.wind?.bearing ?: s?.track
    MapOrientation.HEADING_UP -> null
}

@Composable
private fun Instruments(
    state: FlightState?,
    circling: Boolean,
    tiles: List<TileValue>?,
    onEditTile: (Int) -> Unit,
    onMacCreadyChange: (Double) -> Unit,
    onSetMacCready: (Double) -> Unit,
    onSelectMode: (circling: Boolean) -> Unit,
    menu: List<MenuAction>,
) {
    VarioPanel(state?.vario, state?.averageVario, state?.nettoVario, Modifier.fillMaxWidth())

    // XCSoar's InfoBoxes from the core; a long press picks another
    val colors = XcsTheme.colors
    val boxes = tiles?.map { it.toInfoBoxValue(colors) } ?: infoBoxes(state, circling)
    boxes.chunked(3).forEachIndexed { r, row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEachIndexed { c, box ->
                InfoBox(box.title, box.value, Modifier.weight(1f).fillMaxHeight(), box.color,
                        box.comment, box.commentColor,
                        onLongClick = if (tiles != null) ({ onEditTile(r * 3 + c) }) else null)
            }
        }
    }

    // both tiles as tall as the taller one
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MacCreadyControl(state?.macCready, onMacCreadyChange,
                         Modifier.weight(1f).fillMaxHeight())
        val thermal = state?.currentThermal
        if (circling && thermal != null && thermal.lift > 0)
            SetMacCreadyButton(thermal.lift,
                               { onSetMacCready(Format.stepVerticalSpeed(thermal.lift, 0)) },
                               Modifier.weight(1f).fillMaxHeight())
        else
            FinalGlideTile(state?.finalGlide, Modifier.weight(1f).fillMaxHeight())
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FlightModeSwitch(circling, onSelectMode, Modifier.weight(1f))
        FlightMenuButton(menu)
    }
}

private class InfoBoxValue(val title: String, val value: Format.Value,
                           val color: Color? = null, val comment: String = "",
                           val commentColor: Color? = null)

/** InfoBoxLook's colours by function (doc/architecture.rst). */
private fun tileColor(code: Int, colors: XcsColors): Color? = when (code) {
    TileValue.COLOR_RED -> colors.warning
    TileValue.COLOR_BLUE -> colors.neutralSafe
    TileValue.COLOR_GREEN -> colors.safe
    TileValue.COLOR_YELLOW -> colors.caution
    TileValue.COLOR_MAGENTA -> colors.task
    else -> null
}

private fun TileValue.toInfoBoxValue(colors: XcsColors) =
    InfoBoxValue(title, Format.Value(value, unit), tileColor(color, colors), comment,
                 tileColor(commentColor, colors))

/**
 * The design's six tiles from the snapshot, for previews and the fake
 * core; with the native core, the tiles are XCSoar's InfoBoxes.
 */
@Composable
private fun infoBoxes(s: FlightState?, circling: Boolean): List<InfoBoxValue> {
    val colors = XcsTheme.colors
    fun climbColor(v: Double?) = when {
        v == null -> null
        v >= 0.05 -> colors.lift
        v <= -0.05 -> colors.sink
        else -> null
    }
    val altitude = InfoBoxValue("Altitude", Format.altitude(s?.navAltitude))
    val agl = InfoBoxValue("AGL", Format.altitude(s?.altitudeAgl))
    // the design's boxes; their values and validity are XCSoar's InfoBoxes
    return if (circling) {
        val thermal = s?.currentThermal
        val wind = s?.wind
        listOf(
            InfoBoxValue("Thermal avg", Format.vario(thermal?.lift), climbColor(thermal?.lift)),
            InfoBoxValue("Gained", Format.altitudeDifference(thermal?.gain)),
            InfoBoxValue("In thermal", Format.minutesSeconds(thermal?.duration)),
            altitude,
            agl,
            InfoBoxValue("Wind", wind?.let {
                Format.Value(Format.bearing(it.bearing).text + "°",
                             Format.windSpeed(it.speed).let { v -> "${v.text} ${v.unit}" })
            } ?: Format.Value(Format.INVALID, "")),
        )
    } else {
        val last = s?.lastThermal
        listOf(
            altitude,
            agl,
            InfoBoxValue("Speed to fly", Format.speed(s?.speedToFly)),
            InfoBoxValue("L/D req", Format.requiredGlideRatio(s?.ldRequired).let {
                // the current L/D as the unit, like the design's "27 now 34"
                it.copy(unit = s?.ld?.let { ld -> "now ${Format.glideRatio(ld).text}" } ?: "")
            }),
            InfoBoxValue("Ground speed", Format.speed(s?.groundSpeed)),
            InfoBoxValue("Last thermal", Format.vario(last?.lift), climbColor(last?.lift)),
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
