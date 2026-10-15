// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.WindInfo
import org.xcsoar.mobile.core.WindOption
import org.xcsoar.mobile.core.WindSource
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the wind page (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class WindScreenshotTest {
    private val options = mapOf(WindOption.CIRCLING to true, WindOption.ZIG_ZAG to true,
                                WindOption.EXTERNAL to true, WindOption.TRAIL_DRIFT to false)

    @Test
    fun circlingEstimate() = captureRoboImage("src/test/screenshots/wind_circling.png") {
        XcsTheme(dark = false) {
            WindContent(WindInfo(WindSource.CIRCLING, manual = false, speed = 4.5,
                                 bearing = 272.0),
                        options, { _, _ -> }, { _, _ -> }, {}, {})
        }
    }

    @Test
    fun setByHandDark() = captureRoboImage("src/test/screenshots/wind_manual_dark.png") {
        XcsTheme(dark = true) {
            WindContent(WindInfo(WindSource.MANUAL, manual = true, speed = 5.0, bearing = 250.0),
                        options, { _, _ -> }, { _, _ -> }, {}, {})
        }
    }
}
