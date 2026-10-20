// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.AirspaceDetails
import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.core.AtcInfo
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.MapOption
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.PlaneDetails
import org.xcsoar.mobile.core.PlaneInfo
import org.xcsoar.mobile.core.RadioChannel
import org.xcsoar.mobile.core.RadioInfo
import org.xcsoar.mobile.core.StatusPage
import org.xcsoar.mobile.core.StatusPages
import org.xcsoar.mobile.core.StatusRow
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.core.TeamCodeInfo
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.core.TileType
import org.xcsoar.mobile.core.TileValue
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.core.WaypointRef
import org.xcsoar.mobile.core.WindInfo
import org.xcsoar.mobile.core.WindSource
import org.xcsoar.mobile.ui.analysis.AnalysisContent
import org.xcsoar.mobile.ui.crew.CrewContent
import org.xcsoar.mobile.ui.crew.CrewState
import org.xcsoar.mobile.ui.crew.PlaneEditContent
import org.xcsoar.mobile.ui.crew.PlaneEditState
import org.xcsoar.mobile.ui.analysis.AnalysisPage
import org.xcsoar.mobile.ui.flight.FlightContent
import org.xcsoar.mobile.ui.flight.ReplayControls
import org.xcsoar.mobile.ui.flight.ReplaySpeed
import org.xcsoar.mobile.ui.flight.flightMenu
import org.xcsoar.mobile.ui.flights.FlightsContent
import org.xcsoar.mobile.ui.flights.ReplayContent
import org.xcsoar.mobile.ui.map.MapSettingsContent
import org.xcsoar.mobile.ui.settings.WindContent
import org.xcsoar.mobile.ui.setup.FlightSetup
import org.xcsoar.mobile.ui.setup.FlightSetupContent
import org.xcsoar.mobile.ui.status.StatusContent
import org.xcsoar.mobile.ui.task.TaskContent
import org.xcsoar.mobile.ui.task.TaskState
import org.xcsoar.mobile.ui.theme.MIN_TOUCH_TARGET
import org.xcsoar.mobile.ui.theme.XcsTheme
import org.xcsoar.mobile.ui.tiles.AirspaceDetailsContent
import org.xcsoar.mobile.ui.tiles.AirspaceDetailsState
import org.xcsoar.mobile.ui.tiles.AtcContent
import org.xcsoar.mobile.ui.tiles.RadioContent
import org.xcsoar.mobile.ui.tiles.TeamCodeContent
import org.xcsoar.mobile.ui.tiles.TilePickerContent
import org.xcsoar.mobile.ui.tiles.TilePickerState
import org.xcsoar.mobile.ui.waypoints.WaypointsContent
import org.xcsoar.mobile.ui.waypoints.WaypointsState

/**
 * The cockpit's touch targets: everything a pilot taps in flight is at
 * least 56 dp each way (gloves, turbulence; mobile/docs/PLAN.md M7).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class TouchTargetsTest {
    @get:Rule
    val compose = createComposeRule()

    /** Tappable nodes smaller than [min] either way, as "label: W×H dp". */
    private fun small(min: Dp = MIN, content: @Composable () -> Unit): List<String> {
        compose.setContent { XcsTheme(dark = false) { content() } }
        val density = compose.density.density
        return compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node ->
            // its own size (rows scrolled off or cut by the screen edge count
            // whole), or the touch area Compose extends it to (theme)
            val touch = node.touchBoundsInRoot
            val width = maxOf(node.size.width.toFloat(), touch.width) / density
            val height = maxOf(node.size.height.toFloat(), touch.height) / density
            if (width >= min.value - 0.5f && height >= min.value - 0.5f) return@mapNotNull null
            val config = node.config
            val label = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: "?"
            "$label: ${width.toInt()}×${height.toInt()} dp"
        }
    }

    private fun check(content: @Composable () -> Unit) {
        val found = small(content = content)
        assertTrue("touch targets under $MIN:\n  " + found.joinToString("\n  "),
                   found.isEmpty())
    }

    private val tiles = listOf(
        TileValue(118, "Alt", "1842", "m"), TileValue(1, "H AGL", "212", "m"),
        TileValue(43, "Vopt", "142", "km/h"), TileValue(38, "WP GR", "27", ""),
        TileValue(6, "V GND", "118", "km/h"), TileValue(65, "Battery", "87", "%"))

    @Test
    fun flightScreen() = check {
        FlightContent(FakeXcsoarCore.syntheticState(120).copy(replay = true), "Cruise",
                      circling = false, {}, tiles = tiles,
                      warnings = listOf(AirspaceWarningInfo(
                          "1", "near", "LF-R 46 N", "Restricted", "FL95", "SFC",
                          distance = 1800.0, time = 85.0)),
                      replay = ReplayControls(ReplaySpeed(20.0)))
    }

    @Test
    fun flightMenu() = check {
        FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {},
                      menu = flightMenu(canReplay = true, orientation = MapOrientation.TRACK_UP,
                                        varioSound = true),
                      menuOpen = true)
    }

    @Test
    fun teamCode() = check {
        TeamCodeContent(TeamCodeInfo(own = "4A2C", mate = "8B1D",
                                     reference = WaypointRef(1, "Home")), { true }, {}, {})
    }

    @Test
    fun tilePicker() = check {
        TilePickerContent(TilePickerState(TileLayout.CRUISE, tile = 4, current = 6, types = listOf(
            TileType(118, "Altitude (auto)", "Alt", "The altitude XCSoar uses."),
            TileType(6, "Speed ground", "V GND", "Ground speed measured by the GPS."))),
            {}, {})
    }

    /* on the ground */

    @Test
    fun crew() = check {
        CrewContent(CrewState(planes, lastFlown = planes[0].path, selected = planes[1].path,
                              copilots = listOf("John Roe"), copilot = "John Roe"),
                    {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun planeEditor() = check {
        PlaneEditContent(PlaneEditState(path = "/d/planes/D-5678.xcp", registration = "D-5678",
                                        type = "Duo Discus", polarName = "Duo Discus",
                                        details = PlaneDetails(emptyMass = 420.0, referenceMass = 700.0,
                                                               maxBallast = 200.0, dumpTime = 120,
                                                               maxSpeed = 75.0, wingArea = 16.4,
                                                               handicap = 112)),
                         {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test
    fun mapSettings() = check {
        MapSettingsContent(mapOf(MapOption.TERRAIN to 1, MapOption.TOPOGRAPHY to 0),
                           { _, _ -> }, {})
    }

    @Test
    fun flights() = check { FlightsContent(emptyList(), {}, {}) }

    @Test
    fun radio() = check {
        RadioContent(RadioInfo(RadioChannel("120.505", "Info"), RadioChannel()),
                     { _, _ -> }, { _, _ -> true }, {})
    }

    @Test
    fun atc() = check { AtcContent(AtcInfo("Home", "45°N 5°E", 2.0), {}, {}, {}, {}) }

    @Test
    fun airspace() = check {
        AirspaceDetailsContent(AirspaceDetailsState(read = true, details = AirspaceDetails(
            name = "CTR", airspaceClass = "D", type = "CTR", top = "1981 m", base = "GND",
            frequency = "120.505")), {}, {}, {})
    }

    @Test
    fun wind() = check {
        WindContent(WindInfo(WindSource.MANUAL, manual = true, speed = 5.0, bearing = 250.0),
                    emptyMap(), { _, _ -> }, { _, _ -> }, {}, {})
    }

    @Test
    fun flightSetup() = check {
        FlightSetupContent(FlightSetup(ballast = 80.0, maxBallast = 150.0, bugs = 0.9,
                                       wingLoading = 38.2, crewMass = 90.0, qnh = 1021.0),
                           {}, {}, {}, {}, {}, {})
    }

    @Test
    fun task() = check {
        TaskContent(TaskState(TaskInfo(4, "Racing", valid = true, distance = 64_700.0,
                                       active = 1, points = listOf(
            TaskPointInfo(1, "Start", "start", 1, "Start line", 1000.0),
            TaskPointInfo(2, "Turn", "turn", 8, "Turn point cylinder", 500.0, leg = 14_200.0),
            TaskPointInfo(1, "Finish", "finish", 14, "Finish cylinder", 3000.0,
                          leg = 31_900.0)))), {})
    }

    @Test
    fun goTo() = check {
        WaypointsContent(WaypointsState(waypoints = listOf(
            WaypointInfo(1, "Anduze", landable = true, distance = 23_400.0, bearing = 41.0,
                         reachable = true, arrival = 340))), {}, {}, {}, {})
    }

    @Test
    fun status() = check {
        StatusContent(StatusPages(listOf(
            StatusPage("flight", "Flight", listOf(StatusRow("Altitude", "1646 m"))),
            StatusPage("times", "Times", listOf(StatusRow("UTC time", "12:44")))),
            listOf(45.2, 5.7)), "flight", {}, { _, _ -> }, {})
    }

    @Test
    fun analysis() = check { AnalysisContent(null, AnalysisPage.BAROGRAPH, {}, {}) }

    @Test
    fun replay() = check { ReplayContent(emptyList(), ReplaySpeed(), {}, {}, {}, {}, {}) }

    private val planes = listOf(
        PlaneInfo("/d/planes/D-1234.xcp", "D-1234", "XY", "LS 4", "LS-4", 160, false),
        PlaneInfo("/d/planes/D-5678.xcp", "D-5678", "", "Duo Discus", "Duo Discus", 61, true))

    private companion object {
        val MIN = MIN_TOUCH_TARGET
    }
}
