// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.units

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.UnitGroupInfo
import org.xcsoar.mobile.core.UnitInfo
import org.xcsoar.mobile.core.UnitPreset
import org.xcsoar.mobile.core.UnitSettings
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the units screen (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class UnitsScreenshotTest {
    @Test
    fun british() = captureRoboImage("src/test/screenshots/units.png") {
        val u = listOf(UnitInfo(1, "km", 0.001), UnitInfo(2, "NM", 0.00054),
                       UnitInfo(3, "mi", 0.00062), UnitInfo(4, "km/h", 3.6),
                       UnitInfo(5, "kt", 1.94), UnitInfo(6, "mph", 2.24),
                       UnitInfo(7, "m/s", 1.0), UnitInfo(8, "fpm", 196.85),
                       UnitInfo(9, "m", 1.0), UnitInfo(10, "ft", 3.28))
        val speeds = listOf(6, 5, 4, 7)
        XcsTheme(dark = false) {
            UnitsContent(UnitSettings(
                units = u,
                groups = listOf(
                    UnitGroupInfo(UnitGroup.DISTANCE.code, 1, listOf(3, 2, 1)),
                    UnitGroupInfo(UnitGroup.ALTITUDE.code, 10, listOf(10, 9)),
                    UnitGroupInfo(UnitGroup.HORIZONTAL_SPEED.code, 5, speeds),
                    UnitGroupInfo(UnitGroup.VERTICAL_SPEED.code, 5, listOf(5, 7, 8)),
                    UnitGroupInfo(UnitGroup.TASK_SPEED.code, 4, speeds),
                ),
                presets = listOf(UnitPreset("European"), UnitPreset("British"),
                                 UnitPreset("American"), UnitPreset("Australian")),
                preset = 1), {}, { _, _ -> }, {})
        }
    }
}
