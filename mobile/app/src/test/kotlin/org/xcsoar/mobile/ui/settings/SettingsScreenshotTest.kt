// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking
import org.xcsoar.mobile.core.AirspaceAlerts
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the settings screen (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class SettingsScreenshotTest {
    @Test
    fun settings() = captureRoboImage("src/test/screenshots/settings.png") {
        XcsTheme(dark = false) {
            SettingsContent(onBack = {})
        }
    }

    @Test
    fun airspaceAlerts() = captureRoboImage("src/test/screenshots/airspace_alerts.png") {
        XcsTheme(dark = false) {
            AirspaceAlertsContent(AirspaceAlerts(vibration = false, autoHideSeconds = 10),
                                  { _, _ -> }, {},
                                  runBlocking { FakeXcsoarCore(this).airspaceClasses() })
        }
    }

    /** Warnings off: the other options are dimmed, and a reminder shows. */
    @Test
    fun airspaceAlertsOff() = captureRoboImage("src/test/screenshots/airspace_alerts_off.png") {
        XcsTheme(dark = false) {
            AirspaceAlertsContent(AirspaceAlerts(warnings = false), { _, _ -> }, {})
        }
    }
}
