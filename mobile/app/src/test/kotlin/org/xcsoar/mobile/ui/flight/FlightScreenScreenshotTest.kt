// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.ThermalBandInfo
import org.xcsoar.mobile.core.TileGraphic
import org.xcsoar.mobile.core.TileValue
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * L4: the flight screen against the fake core, in each theme and
 * orientation (mobile/docs/ARCHITECTURE.md §6).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FlightScreenScreenshotTest {
    private fun capture(name: String, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/$name.png", content = content)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun cruiseSunlight() = capture("cruise_sunlight") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun circlingSunlight() = capture("circling_sunlight") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(320), null, circling = true, {})
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun cruiseNight() = capture("cruise_night") {
        XcsTheme(dark = true) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
        }
    }

    /**
     * The core's tiles: one with a comment (the altitude in feet) is as
     * high as the others.
     */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun tilesWithComment() = capture("tiles_comment") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {},
                          tiles = listOf(
                              TileValue(1, "Alt GPS", "1646", "m", comment = "5400 ft"),
                              TileValue(2, "H AGL", "1046", "m", comment = "3432 ft"),
                              TileValue(3, "V Opt", "126", "km/h"),
                              TileValue(4, "Next Dist", "22.8", "km",
                                        comment = "Grenoble Le Versoud"),
                              TileValue(5, "V GND", "130", "km/h"),
                              TileValue(6, "TC 30s", "+2.1", "m/s",
                                        color = TileValue.COLOR_GREEN)))
        }
    }

    /**
     * Graphical InfoBoxes: the vario and thermal sparks, the barogram,
     * the wind and next waypoint arrows, drawn from the core's data.
     */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun graphicTiles() = capture("tiles_graphic") {
        val vario = (0 until 24).map { 2.2 * sin(it / 3.5) - 0.4 }
        val barogram = (0..40).map { listOf(it / 20.0, 600 + 900 * sin(it / 13.0) + 15 * (it % 5)) }
        val terrain = (0..40).map { listOf(it / 20.0, 350 + 120 * sin(it / 6.0)) }
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(320), null, circling = true, {},
                          tiles = listOf(
                              TileValue(24, "Vario Trace", "", comment = "+1.6 m/s",
                                        graphic = TileGraphic(TileGraphic.SPARK, vario, 30,
                                                              center = true, macCready = 1.5,
                                                              scale = listOf(-5.0, 5.0))),
                              TileValue(79, "Barogram", "", comment = "1420 m",
                                        graphic = TileGraphic(TileGraphic.BAROGRAM, pointsRaw = barogram,
                                                              terrainRaw = terrain)),
                              TileValue(27, "TC Trace", "", comment = "+1.2 m/s",
                                        graphic = TileGraphic(TileGraphic.SPARK,
                                                              vario.map { maxOf(it, 0.3) }, 30,
                                                              macCready = 1.5,
                                                              scale = listOf(0.0, 5.0))),
                              TileValue(46, "Wind", "", comment = "250° / 22",
                                        graphic = TileGraphic(TileGraphic.WIND_ARROW,
                                                              angle = 110.0)),
                              TileValue(85, "Grenoble Le Versoud", "", comment = "22.8 km",
                                        graphic = TileGraphic(TileGraphic.NEXT_ARROW,
                                                              angle = 320.0)),
                              TileValue(6, "V GND", "130", "km/h")))
        }
    }

    /**
     * Task progress (five points, two achieved, the third active), the
     * climb percentage pie and the thermal band, drawn from the core's
     * data.
     */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun graphicTilesTaskAndClimb() = capture("tiles_graphic_task_climb") {
        val task = listOf(listOf(0.0, 1.0), listOf(0.3, 1.0), listOf(0.55, 0.0),
                          listOf(0.8, 0.0), listOf(1.0, 0.0))
        // climbs strongest in the middle of the band, this one weaker
        val band = ThermalBandInfo(
            height = 900.0, macCready = 1.5, range = listOf(0.0, 1600.0),
            collectionRaw = (0..15).map { listOf(2.4 * sin(it / 15.0 * 3.1), it * 100.0) },
            currentRaw = (0..9).map { listOf(1.4 * sin(it / 9.0 * 3.1), it * 100.0) })
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {},
                          tiles = listOf(
                              TileValue(0, "Progress", "",
                                        graphic = TileGraphic(TileGraphic.TASK_PROGRESS,
                                                              pointsRaw = task, active = 2,
                                                              remaining = 0.52)),
                              TileValue(0, "Climb %", "",
                                        graphic = TileGraphic(TileGraphic.CLIMB_PERCENT,
                                                              listOf(28.0, 41.0, 9.0))),
                              TileValue(0, "Th Band", "",
                                        graphic = TileGraphic(TileGraphic.THERMAL_BAND,
                                                              band = band)),
                              TileValue(6, "V GND", "130", "km/h"),
                              TileValue(6, "V GND", "130", "km/h"),
                              TileValue(6, "V GND", "130", "km/h")))
        }
    }

    /* lift strongest towards the north-east of a left-hand circle */
    private val assistant = TileGraphic(
        TileGraphic.THERMAL_ASSISTANT, circling = true, left = true, heading = 200.0,
        average = 1.6, lift = (0 until 36).map { 1.4 + 1.3 * cos(Math.toRadians(it * 10.0 - 320)) })

    /**
     * Circling: the thermal assistant on the glider, in the map's
     * directions (north up here), and as a tile, heading up.
     */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun circlingThermalAssistant() = capture("circling_thermal_assistant") {
        XcsTheme(dark = false) {
            val colors = XcsTheme.colors
            FlightContent(
                FakeXcsoarCore.syntheticState(320), null, circling = true, {},
                map = MapSlot(content = { Box(it.background(colors.background)) },
                              onAircraftPosition = { _, _ -> }, onZoom = {},
                              orientation = MapOrientation.NORTH_UP,
                              thermalAssistant = assistant),
                tiles = listOf(
                    TileValue(0, "Th Assist", "", graphic = assistant),
                    TileValue(6, "TC Avg", "+1.6", "m/s"),
                    TileValue(6, "TC Gain", "380", "m"),
                    TileValue(6, "Alt", "1646", "m"),
                    TileValue(6, "AGL", "1046", "m"),
                    TileValue(6, "Wind", "270°", "16 km/h")))
        }
    }

    /** Before the first snapshot every value shows dashes. */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun noData() = capture("no_data") {
        XcsTheme(dark = false) {
            FlightContent(null, null, circling = false, {})
        }
    }

    /** Airspace warnings turned off: a reminder on the map. */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun airspaceAlertsOff() = capture("airspace_alerts_off_chip") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {},
                          airspaceAlertsOff = true)
        }
    }

    /** No plane set up: no polar, so no final glide or speed to fly. */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun noPolar() = capture("no_polar_chip") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120).copy(noPolar = true), null,
                          circling = false, {})
        }
    }

    /** The menu open over the flight screen. */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun menu() = capture("flight_menu") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {},
                          menu = flightMenu(canReplay = true,
                                            orientation = MapOrientation.TRACK_UP,
                                            varioSound = true),
                          menuOpen = true)
        }
    }

    /** A paused replay: its controls take the place of the chip. */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun replay() = capture("replay_bar") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120).copy(replay = true), null,
                          circling = false, {},
                          replay = ReplayControls(ReplaySpeed(20.0, paused = true)))
        }
    }

    /**
     * A short phone (a Redmi 5: 360x720 dp) in a replay: the cards leave
     * little room above the map buttons, and the vario stays, smaller.
     */
    @Test
    @Config(qualifiers = "w360dp-h720dp-xhdpi")
    fun shortPhoneReplay() = capture("short_phone_replay") {
        XcsTheme(dark = true) {
            FlightContent(FakeXcsoarCore.syntheticState(320).copy(replay = true), null,
                          circling = true, {},
                          replay = ReplayControls(ReplaySpeed(10.0, paused = false)))
        }
    }

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xxhdpi")
    fun landscape() = capture("landscape_sunlight") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
        }
    }

    /** A 10" tablet, upright and on its side. */
    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tabletPortrait() = capture("tablet_portrait") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun tabletLandscape() = capture("tablet_landscape") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class AirspaceWarningScreenshotTest {
    @Test
    fun insideAndAhead() = captureRoboImage("src/test/screenshots/airspace_warning.png") {
        XcsTheme(dark = false) {
            FlightContent(
                FakeXcsoarCore.syntheticState(120), null, circling = false, {},
                warnings = listOf(
                    org.xcsoar.mobile.core.AirspaceWarningInfo(
                        "1", "near", "LF-R 46 N MONTAGNE NOIRE", "Restricted", "FL95", "SFC",
                        distance = 1800.0, time = 85.0),
                    org.xcsoar.mobile.core.AirspaceWarningInfo(
                        "2", "task", "CTA LIMOGES", "Class D", "FL145", "FL115")))
        }
    }
}
