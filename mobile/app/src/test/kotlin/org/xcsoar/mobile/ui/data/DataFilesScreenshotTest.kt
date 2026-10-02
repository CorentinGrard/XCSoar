// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.data

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.DataStatus
import org.xcsoar.mobile.core.FileStatus
import org.xcsoar.mobile.core.MapStatus
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the data files screen (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class DataFilesScreenshotTest {
    @Test
    fun withError() = captureRoboImage("src/test/screenshots/data_files.png") {
        XcsTheme(dark = false) {
            DataFilesContent(
                DataFilesState(
                    DataStatus(
                        MapStatus(listOf("/data/XCSoarData/maps/alps_hd.xcm"), terrain = true),
                        FileStatus(emptyList(), 0),
                        FileStatus(listOf("/data/XCSoarData/waypoints/alps.cup"), 1250)),
                    error = "Airspace: unknown file format"),
                {}, {}, {})
        }
    }
}
