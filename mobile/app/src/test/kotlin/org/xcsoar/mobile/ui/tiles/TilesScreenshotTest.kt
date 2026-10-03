// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.tiles

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.core.TileType
import org.xcsoar.mobile.core.TileValue
import org.xcsoar.mobile.ui.flight.FlightContent
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the flight screen with XCSoar's InfoBoxes, and the tile picker. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class TilesScreenshotTest {
    @Test
    fun flightScreenWithInfoBoxes() = captureRoboImage("src/test/screenshots/flight_tiles.png") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), "Cruise", circling = false, {},
                          tiles = listOf(
                              TileValue(118, "Alt", "1842", "m"),
                              TileValue(1, "H AGL", "212", "m", color = TileValue.COLOR_RED),
                              TileValue(43, "Vopt", "142", "km/h", "DOLPHIN"),
                              TileValue(38, "WP GR", "27", "", "Anduze"),
                              TileValue(6, "V GND", "118", "km/h"),
                              TileValue(65, "Battery", "87", "%", "AC"),
                          ))
        }
    }

    @Test
    fun picker() = captureRoboImage("src/test/screenshots/tile_picker.png") {
        XcsTheme(dark = false) {
            TilePickerContent(TilePickerState(
                TileLayout.CRUISE, tile = 4, current = 6, types = listOf(
                    TileType(118, "Altitude (auto)", "Alt",
                             "The altitude XCSoar uses: barometric if available, else GPS."),
                    TileType(65, "Battery", "Battery", "Battery level of the device."),
                    TileType(43, "Speed dolphin", "Vopt",
                             "Instantaneous MacCready speed-to-fly, making use of netto vario."),
                    TileType(6, "Speed ground", "V GND", "Ground speed measured by the GPS."),
                    TileType(7, "Thermal last", "TL Avg",
                             "Total altitude gain/loss in the last thermal divided by the time."),
                )), {}, {})
        }
    }
}
