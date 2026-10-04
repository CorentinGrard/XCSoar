// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.AirspaceAlerts
import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.core.TileValue
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.ThemeChoice
import org.xcsoar.mobile.ui.theme.XcsColors
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun FlightScreen(
    viewModel: FlightViewModel,
    onOpenDataFiles: () -> Unit = {},
    onOpenWaypoints: () -> Unit = {},
    onOpenFlightSetup: () -> Unit = {},
    onOpenFlights: () -> Unit = {},
    onOpenTask: () -> Unit = {},
    onOpenAnalysis: () -> Unit = {},
    onOpenCrew: () -> Unit = {},
    onOpenAirspaceAlerts: () -> Unit = {},
    onOpenWeather: () -> Unit = {},
    onOpenNotams: () -> Unit = {},
    onOpenPilot: () -> Unit = {},
    onOpenUnits: () -> Unit = {},
    /** null when the core draws no map */
    onOpenMapSettings: (() -> Unit)? = null,
    onOpenSafety: () -> Unit = {},
    onOpenVarioSound: () -> Unit = {},
    onOpenTracking: () -> Unit = {},
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    onTheme: (ThemeChoice) -> Unit = {},
    onEditTile: (TileLayout, Int) -> Unit = { _, _ -> },
    /** the menu is open (kept under the pages it opens) */
    menuOpen: Boolean = false,
    onMenuOpen: (Boolean) -> Unit = {},
    /** a page lies over the flight screen */
    covered: Boolean = false,
) {
    val state by viewModel.flightState.collectAsStateWithLifecycle()
    // read where it is shown, so only the vario bar recomposes with it
    val vario = viewModel.vario.collectAsStateWithLifecycle()
    val lastEvent by viewModel.lastEvent.collectAsStateWithLifecycle()
    val circling by viewModel.showCircling.collectAsStateWithLifecycle()
    val tileLayout by viewModel.tileLayout.collectAsStateWithLifecycle()
    val mapFollows by viewModel.mapFollows.collectAsStateWithLifecycle()
    val mapOrientation by viewModel.mapOrientation.collectAsStateWithLifecycle()
    val mapItems by viewModel.mapItems.collectAsStateWithLifecycle()
    val warnings by viewModel.airspaceWarnings.collectAsStateWithLifecycle()
    val varioSound by viewModel.varioSound.collectAsStateWithLifecycle()
    val macCready by viewModel.macCready.collectAsStateWithLifecycle()
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val hideTimer by viewModel.airspaceHideTimer.collectAsStateWithLifecycle()
    val airspaceAlerts by viewModel.airspaceAlerts.collectAsStateWithLifecycle()
    // back from a page: the airspace alerts or the vario sound may have changed
    LaunchedEffect(covered) {
        if (!covered) {
            viewModel.refreshAirspaceAlerts()
            viewModel.refreshVarioSound()
        }
    }
    BackHandler(enabled = menuOpen && !covered) { onMenuOpen(false) }

    FlightContent(
        state = state,
        lastEvent = lastEvent,
        circling = circling,
        tiles = tiles,
        vario = { vario.value },
        macCready = macCready,
        onEditTile = { tile ->
            onEditTile(if (circling) TileLayout.CIRCLING else TileLayout.CRUISE, tile)
        },
        onMacCreadyChange = viewModel::setMacCready,
        onSetMacCready = viewModel::setMacCready,
        warnings = warnings,
        airspaceHideTimer = hideTimer,
        airspaceAlertsOff = !airspaceAlerts.warnings,
        onAcknowledge = viewModel::acknowledgeAirspace,
        onNextWaypoint = onOpenWaypoints,
        menuOpen = menuOpen,
        onMenuOpen = onMenuOpen,
        menu = flightMenu(
            onOpenWaypoints, onOpenTask, onOpenFlightSetup, onOpenAnalysis, onOpenCrew,
            onOpenFlights, onOpenDataFiles, onOpenAirspaceAlerts,
            onOpenWeather = onOpenWeather,
            onOpenNotams = onOpenNotams,
            onOpenPilot = onOpenPilot,
            onOpenUnits = onOpenUnits,
            onOpenMapSettings = onOpenMapSettings,
            onOpenSafety = onOpenSafety,
            onOpenVarioSound = onOpenVarioSound,
            onOpenTracking = onOpenTracking,
            airspaceAlerts = airspaceAlerts,
            replay = state?.replay == true,
            // on the ground only: a demo, not something to press in flight
            canReplay = state?.flying == false,
            onReplay = viewModel::replayDemo,
            onStopReplay = viewModel::stopReplay,
            tileLayout = tileLayout,
            onTileLayout = viewModel::selectFlightMode,
            orientation = mapOrientation.takeIf { viewModel.hasMap },
            onOrientation = viewModel::setMapOrientation,
            varioSound = varioSound,
            onVarioSound = viewModel::setVarioSound,
            theme = theme,
            onTheme = onTheme,
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
 * @param orientation which way is up, for the north mark; null hides it
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
    val onHold: (x: Int, y: Int) -> Unit = { _, _ -> },
    val items: List<MapItemInfo>? = null,
    val onCloseItems: () -> Unit = {},
    val onGoto: (waypointId: Int) -> Unit = {},
)

/**
 * The flight screen, map first: the map with the vario and final glide
 * bars on its edges, and the tiles with MacCready and the menu below it
 * (portrait) or beside it (landscape, with the next waypoint).
 * Without [map] (no native core) the map area only shows the glider
 * symbol.
 */
@Composable
fun FlightContent(
    state: FlightState?,
    lastEvent: String?,
    circling: Boolean,
    onMacCreadyChange: (Double) -> Unit,
    onSetMacCready: (Double) -> Unit = {},
    menu: FlightMenu = FlightMenu(),
    menuOpen: Boolean = false,
    onMenuOpen: (Boolean) -> Unit = {},
    map: MapSlot? = null,
    warnings: List<AirspaceWarningInfo> = emptyList(),
    /** the first warning hides itself then; null: it stays */
    airspaceHideTimer: HideTimer? = null,
    /** the pilot turned airspace warnings off: a reminder on the map */
    airspaceAlertsOff: Boolean = false,
    onAcknowledge: (AirspaceWarningInfo, day: Boolean) -> Unit = { _, _ -> },
    onNextWaypoint: () -> Unit = {},
    tiles: List<TileValue>? = null,
    onEditTile: (Int) -> Unit = {},
    /** Faster than [state]; from [state] when null (previews). */
    vario: (() -> VarioValues?)? = null,
    /** As the pilot set it, before [state] has it. */
    macCready: Double? = state?.macCready,
) {
    val colors = XcsTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val instruments = @Composable {
            Instruments(state, macCready, circling, tiles, onEditTile, onMacCreadyChange,
                        onMenu = { onMenuOpen(true) })
        }
        val mapArea = @Composable { showNext: Boolean, modifier: Modifier,
                                    insets: WindowInsets ->
            MapArea(state, lastEvent, circling, map, warnings, airspaceHideTimer,
                    airspaceAlertsOff, onAcknowledge, onNextWaypoint, onSetMacCready, showNext,
                    vario, modifier, insets)
        }

        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                mapArea(false, Modifier.weight(1f).fillMaxHeight(),
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical +
                                                      WindowInsetsSides.Start))
                Column(
                    Modifier
                        .width(400.dp)
                        .fillMaxHeight()
                        .background(colors.sheet)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Vertical + WindowInsetsSides.End))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NextWaypointCard(state?.next, Modifier.fillMaxWidth(),
                                     state?.nextTimeRemaining, onClick = onNextWaypoint,
                                     elevated = false)
                    instruments()
                }
            }
        } else {
            // the sheet may scroll on short screens; the map keeps most of
            // the height
            val sheetMax = maxHeight - 240.dp
            Column(Modifier.fillMaxSize()) {
                mapArea(true, Modifier.weight(1f).fillMaxWidth(),
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
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { instruments() }
            }
        }

        FlightMenuSheet(menuOpen, menu, onClose = { onMenuOpen(false) })
    }
}

@Composable
private fun MapArea(
    state: FlightState?,
    lastEvent: String?,
    circling: Boolean,
    map: MapSlot?,
    warnings: List<AirspaceWarningInfo>,
    hideTimer: HideTimer?,
    alertsOff: Boolean,
    onAcknowledge: (AirspaceWarningInfo, day: Boolean) -> Unit,
    onNextWaypoint: () -> Unit,
    onSetMacCready: (Double) -> Unit,
    /** false in landscape, where the side panel shows it */
    showNext: Boolean,
    vario: (() -> VarioValues?)?,
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
                                      Modifier.fillMaxWidth(), hideTimer)
            }
            if (showNext)
                NextWaypointCard(state?.next, Modifier.fillMaxWidth(), state?.nextTimeRemaining,
                                 onClick = onNextWaypoint)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state == null -> StatusChip("Starting…", colors.textSecondary)
                    else -> {
                        WindChip(state.wind)
                        if (state.position == null) StatusChip("NO GPS", colors.caution)
                        if (alertsOff) StatusChip("AIRSPACE ALERTS OFF", colors.caution)
                        if (state.replay) StatusChip("REPLAY", colors.neutralSafe)
                        if (!state.flying) StatusChip("On ground", colors.text)
                    }
                }
                if (lastEvent != null) StatusChip(lastEvent, colors.textSecondary)
            }
        }

        // below the cards: the vario on the left edge, final glide on the
        // right, the map buttons along the bottom, "Set MC" on the left
        // in lift
        val cardsHeight = with(LocalDensity.current) { cardsBottom.toDp() }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(top = cardsHeight)
                .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal +
                                                 WindowInsetsSides.Bottom))
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            // what is left above the map buttons
            val barSpace = maxHeight - 56.dp - 12.dp
            if (barSpace >= 120.dp) {
                VarioSlot(vario, state, Modifier.align(Alignment.TopStart)
                    .height(barSpace.coerceAtMost(300.dp)))
                FinalGlideBar(state?.finalGlide, Modifier.align(Alignment.TopEnd)
                    .height(barSpace.coerceAtMost(180.dp)))
            }

            map?.items?.let {
                MapItemsCard(it, map.onCloseItems, map.onGoto, Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 64.dp))
            }

            val thermal = state?.currentThermal
            if (circling && thermal != null && thermal.lift > 0)
                SetMacCreadyButton(thermal.lift,
                                   { onSetMacCready(Format.stepVerticalSpeed(thermal.lift, 0)) },
                                   Modifier.align(Alignment.BottomStart))

            Row(
                Modifier.align(Alignment.BottomEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (map != null) {
                    // a turned map shows where north is
                    map.orientation?.takeIf { it != MapOrientation.NORTH_UP }
                        ?.let { mapAngle(it, state) }
                        ?.let { NorthMark(it) }
                    if (!map.follows)
                        CentreButton(map.onFollow)
                    ZoomButtons(map.onZoom)
                }
            }
        }
    }
}

/**
 * The direction at the top of the map, for the north mark: the same
 * references the core turns the map with
 * (core/map/CoreMap.cpp ScreenAngle()); null when the core has none.
 */
private fun mapAngle(orientation: MapOrientation, s: FlightState?): Double? = when (orientation) {
    MapOrientation.NORTH_UP -> 0.0
    MapOrientation.TRACK_UP -> s?.track
    MapOrientation.TARGET_UP -> s?.next?.bearing ?: s?.track
    MapOrientation.WIND_UP -> s?.wind?.bearing ?: s?.track
    MapOrientation.HEADING_UP -> null
}

/** The tiles' columns; MacCready spans two of them. */
private const val TILE_COLUMNS = 3

/**
 * XCSoar's InfoBoxes from the core (a long press picks another), then
 * MacCready over two columns and the menu: one grid, every cell as
 * high as the others.
 */
@Composable
private fun Instruments(
    state: FlightState?,
    macCready: Double?,
    circling: Boolean,
    tiles: List<TileValue>?,
    onEditTile: (Int) -> Unit,
    onMacCreadyChange: (Double) -> Unit,
    onMenu: () -> Unit,
) {
    val colors = XcsTheme.colors
    val boxes = tiles?.map { it.toInfoBoxValue(colors) } ?: infoBoxes(state, circling)
    TileGrid(boxes.map { 1 } + listOf(2, 1), Modifier.fillMaxWidth()) {
        boxes.forEachIndexed { i, box ->
            InfoBox(box.title, box.value, Modifier, box.color, box.comment, box.commentColor,
                    onLongClick = if (tiles != null) ({ onEditTile(i) }) else null)
        }
        MacCreadyControl(macCready, onMacCreadyChange)
        MenuTile(onMenu)
    }
}

/**
 * Lays its children out in rows of [TILE_COLUMNS], each over
 * [spans] columns (in the children's order), all as high as the
 * highest: a tile with a comment is no taller than one without.
 */
@Composable
private fun TileGrid(spans: List<Int>, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val cell = (constraints.maxWidth - gap * (TILE_COLUMNS - 1)) / TILE_COLUMNS
        fun width(span: Int) = cell * span + gap * (span - 1)

        val height = measurables.withIndex()
            .maxOfOrNull { (i, m) -> m.minIntrinsicHeight(width(spans[i])) } ?: 0
        // the column and row of each child
        var column = 0
        var row = 0
        val cells = spans.map { span ->
            if (column + span > TILE_COLUMNS) {
                column = 0
                row++
            }
            (column to row).also { column += span }
        }
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(Constraints.fixed(width(spans[i]), height))
        }
        val rows = if (spans.isEmpty()) 0 else row + 1
        layout(constraints.maxWidth, (rows * height + (rows - 1) * gap).coerceAtLeast(0)) {
            placeables.forEachIndexed { i, p ->
                val (c, r) = cells[i]
                p.place(c * (cell + gap), r * (height + gap))
            }
        }
    }
}

/**
 * The vario bar, reading [vario] in its own scope: it changes five
 * times a second, the rest of the screen once.  Its own layer, too, so
 * redrawing it leaves the rest alone.
 */
@Composable
private fun VarioSlot(vario: (() -> VarioValues?)?, state: FlightState?, modifier: Modifier) {
    val v = vario?.invoke()
        ?: state?.let { VarioValues(it.vario, it.averageVario, it.nettoVario) }
    VarioBar(v?.vario, v?.average, v?.netto, modifier.graphicsLayer())
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
        FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun CirclingPreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(320), null, circling = true, {})
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

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MenuPreview() {
    XcsTheme(dark = false) {
        FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {},
                      menu = flightMenu(), menuOpen = true)
    }
}

/**
 * The flight menu: in-flight actions, the flight screen's quick
 * settings, then what is done before and after the flight and the
 * settings.  Each page is listed once.
 */
fun flightMenu(
    onOpenWaypoints: () -> Unit = {},
    onOpenTask: () -> Unit = {},
    onOpenFlightSetup: () -> Unit = {},
    onOpenAnalysis: () -> Unit = {},
    onOpenCrew: () -> Unit = {},
    onOpenFlights: () -> Unit = {},
    onOpenDataFiles: () -> Unit = {},
    onOpenAirspaceAlerts: () -> Unit = {},
    onOpenWeather: () -> Unit = {},
    onOpenNotams: () -> Unit = {},
    onOpenPilot: () -> Unit = {},
    onOpenUnits: () -> Unit = {},
    /** null when the core draws no map */
    onOpenMapSettings: (() -> Unit)? = {},
    onOpenSafety: () -> Unit = {},
    onOpenVarioSound: () -> Unit = {},
    onOpenTracking: () -> Unit = {},
    airspaceAlerts: AirspaceAlerts = AirspaceAlerts(),
    replay: Boolean = false,
    canReplay: Boolean = false,
    onReplay: () -> Unit = {},
    onStopReplay: () -> Unit = {},
    tileLayout: Boolean? = null,
    onTileLayout: (circling: Boolean?) -> Unit = {},
    orientation: MapOrientation? = null,
    onOrientation: (MapOrientation) -> Unit = {},
    varioSound: Boolean? = null,
    onVarioSound: (Boolean) -> Unit = {},
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    onTheme: (ThemeChoice) -> Unit = {},
) = FlightMenu(
    inFlight = listOf(
        MenuAction("Go to waypoint", "Nearest landable first", onClick = onOpenWaypoints),
        MenuAction("Task", "Edit, next, previous", onClick = onOpenTask),
        MenuAction("Flight setup", "Ballast, bugs, QNH", onClick = onOpenFlightSetup),
        MenuAction("Analysis", "Barograph, climb, contest", onClick = onOpenAnalysis),
    ),
    sections = listOf(
        MenuSection("Before and after the flight", listOf(
            MenuAction("Aircraft & crew", "Plane, polar, masses, co-pilot",
                       onClick = onOpenCrew),
            MenuAction("Weather", "METAR, TAF, RASP", onClick = onOpenWeather),
            MenuAction("NOTAMs", "Download, filters, list", onClick = onOpenNotams),
            MenuAction("Flights", "Share, upload to WeGlide", onClick = onOpenFlights),
            if (replay)
                MenuAction("Stop replay", "Back to the GPS", closesMenu = true,
                           onClick = onStopReplay)
            else
                MenuAction("Replay demo", "A demo flight, on the ground only",
                           enabled = canReplay, closesMenu = true, onClick = onReplay),
        )),
        MenuSection("Settings", listOfNotNull(
            MenuAction("Airspace", describe(airspaceAlerts), onClick = onOpenAirspaceAlerts),
            MenuAction("Safety heights", "Arrival, terrain, safety MC", onClick = onOpenSafety),
            MenuAction("Vario sound", "Volume, mode, dead band", onClick = onOpenVarioSound),
            onOpenMapSettings?.let {
                MenuAction("Map", "Terrain, topography, trail", onClick = it)
            },
            MenuAction("Units", "Altitude, speed, lift…", onClick = onOpenUnits),
            MenuAction("Pilot & WeGlide", "Name, WeGlide ID", onClick = onOpenPilot),
            MenuAction("Live tracking", "Cloud, SkyLines, LiveTrack24",
                       onClick = onOpenTracking),
            MenuAction("Data files", "Map, airspace, waypoints", onClick = onOpenDataFiles),
        )),
    ),
    tileLayout = tileLayout,
    onTileLayout = onTileLayout,
    orientation = orientation,
    onOrientation = onOrientation,
    varioSound = varioSound,
    onVarioSound = onVarioSound,
    theme = theme,
    onTheme = onTheme,
)

/** "On · sound · vibration", "Off": the airspace alerts at a glance. */
private fun describe(alerts: AirspaceAlerts): String =
    if (!alerts.warnings) "Off"
    else listOfNotNull("On",
                       "sound".takeIf { alerts.sound },
                       "vibration".takeIf { alerts.vibration },
                       "hide after ${alerts.autoHideSeconds} s"
                           .takeIf { alerts.autoHideSeconds > 0 })
        .joinToString(" · ")
