// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.SafetyOption
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the safety heights page (mobile/docs/ARCHITECTURE.md §6), all of it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h1200dp-xxhdpi")
class SafetyScreenshotTest {
    @Test
    fun safetyHeights() = captureRoboImage("src/test/screenshots/safety.png") {
        XcsTheme(dark = false) {
            SafetyContent(mapOf(SafetyOption.ARRIVAL_HEIGHT to 300.0,
                                SafetyOption.TERRAIN_HEIGHT to 150.0,
                                SafetyOption.MC to 0.5,
                                SafetyOption.STARTUP_MC to 0.0,
                                SafetyOption.RISK_FACTOR to 0.3,
                                SafetyOption.ALTERNATES to 0.0,
                                SafetyOption.TURN_BACK_MARKER to 1.0),
                          { _, _ -> }, {})
        }
    }
}
