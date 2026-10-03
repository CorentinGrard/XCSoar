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
import org.xcsoar.mobile.core.SafetyOption
import org.xcsoar.mobile.core.SoundOption
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

    @Test
    fun safety() = captureRoboImage("src/test/screenshots/safety.png") {
        XcsTheme(dark = false) {
            SafetyContent(
                mapOf(SafetyOption.ARRIVAL_HEIGHT to 300.0, SafetyOption.TERRAIN_HEIGHT to 150.0,
                      SafetyOption.MC to 0.5, SafetyOption.RISK_FACTOR to 0.3,
                      SafetyOption.ALTERNATES to 0.0, SafetyOption.TURN_BACK_MARKER to 1.0),
                { _, _ -> }, {})
        }
    }

    @Test
    fun varioSound() = captureRoboImage("src/test/screenshots/vario_sound.png") {
        XcsTheme(dark = false) {
            VarioSoundContent(
                mapOf(SoundOption.VARIO to 1, SoundOption.VARIO_VOLUME to 80,
                      SoundOption.VARIO_SWITCHING to 1, SoundOption.VARIO_DEAD_BAND to 1,
                      SoundOption.VARIO_DEAD_BAND_MIN to -30,
                      SoundOption.VARIO_DEAD_BAND_MAX to 10),
                { _, _ -> }, {})
        }
    }
}
