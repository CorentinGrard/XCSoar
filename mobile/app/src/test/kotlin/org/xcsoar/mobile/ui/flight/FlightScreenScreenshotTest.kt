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
            FlightContent(FakeXcsoarCore.syntheticState(120), "Cruise", circling = false, {})
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun circlingSunlight() = capture("circling_sunlight") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(320), "Climb", circling = true, {})
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun cruiseNight() = capture("cruise_night") {
        XcsTheme(dark = true) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
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

    @Test
    @Config(qualifiers = "w844dp-h390dp-land-xxhdpi")
    fun landscape() = capture("landscape_sunlight") {
        XcsTheme(dark = false) {
            FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false, {})
        }
    }
}
