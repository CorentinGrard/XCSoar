// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.map

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.MapOption
import org.xcsoar.mobile.core.TerrainRamp
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the map settings screen (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class MapSettingsScreenshotTest {
    @Test
    fun pastelShortTrail() = captureRoboImage("src/test/screenshots/map_settings.png") {
        XcsTheme(dark = false) {
            MapSettingsContent(
                mapOf(MapOption.TERRAIN to 1, MapOption.TERRAIN_RAMP to TerrainRamp.PASTEL.code,
                      MapOption.TOPOGRAPHY to 0, MapOption.TRAIL to 2),
                { _, _ -> }, {})
        }
    }
}
