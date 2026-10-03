// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.setup

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the flight setup screen (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class FlightSetupScreenshotTest {
    @Test
    fun ballastAndBugs() = captureRoboImage("src/test/screenshots/flight_setup.png") {
        XcsTheme(dark = false) {
            FlightSetupContent(
                FlightSetup(ballast = 80.0, maxBallast = 150.0, bugs = 0.9, wingLoading = 38.2,
                            crewMass = 90.0, qnh = 1021.0, staticPressure = 950.0,
                            baroAltitude = 642.0),
                {}, {}, {}, {}, {}, {})
        }
    }

    @Test
    fun noBarometer() = captureRoboImage("src/test/screenshots/flight_setup_no_baro.png") {
        XcsTheme(dark = false) {
            FlightSetupContent(
                FlightSetup(ballast = 0.0, maxBallast = 0.0, bugs = 1.0, wingLoading = null),
                {}, {}, {}, {}, {}, {})
        }
    }
}
