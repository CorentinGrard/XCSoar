// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.TimeZone

/** L4: the flight list (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w390dp-h844dp-xxhdpi")
class FlightsScreenshotTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun twoFlights() = captureRoboImage("src/test/screenshots/flights.png") {
        XcsTheme(dark = false) {
            FlightsContent(listOf(
                FlightLog("/x/2026-10-02-XCS-AAA-02.igc", "2026-10-02-XCS-AAA-02.igc",
                          1790959200000L, 412_300),
                FlightLog("/x/2026-09-27-XCS-AAA-01.igc", "2026-09-27-XCS-AAA-01.igc",
                          1790521500000L, 98_000),
            ), {}, {}, utc)
        }
    }

    @Test
    fun noFlights() = captureRoboImage("src/test/screenshots/flights_empty.png") {
        XcsTheme(dark = false) {
            FlightsContent(emptyList(), {}, {}, utc)
        }
    }
}
