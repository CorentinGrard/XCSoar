// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.waypoints

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the go-to waypoint list (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class WaypointsScreenshotTest {
    @Test
    fun nearestLandables() = captureRoboImage("src/test/screenshots/waypoints.png") {
        XcsTheme(dark = false) {
            WaypointsContent(
                WaypointsState(waypoints = listOf(
                    WaypointInfo(1, "Anduze", landable = true, elevation = 136.0,
                                 distance = 23400.0, bearing = 41.0, reachable = true,
                                 arrival = 340),
                    WaypointInfo(2, "LFNL St Martin De Londres", landable = true, airport = true,
                                 elevation = 183.0, distance = 31000.0, bearing = 331.0,
                                 reachable = false, arrival = -120))),
                {}, {}, {}, {})
        }
    }
}
