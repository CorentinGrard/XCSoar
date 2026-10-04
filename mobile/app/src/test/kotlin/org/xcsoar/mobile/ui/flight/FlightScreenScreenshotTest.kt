// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.TileValue
import org.xcsoar.mobile.ui.theme.XcsTheme

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

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xxhdpi")
    fun landscape() = capture("landscape_sunlight") {
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
