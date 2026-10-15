// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.status

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.StatusPage
import org.xcsoar.mobile.core.StatusPages
import org.xcsoar.mobile.core.StatusRow
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the status page (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class StatusScreenshotTest {
    private val fake = StatusPages(listOf(
        StatusPage("flight", "Flight", listOf(
            StatusRow("Location", "N 43°47'54\" E 003°47'05\""),
            StatusRow("Altitude", "1646 m"), StatusRow("Max. height gain", "1250 m"),
            StatusRow("Near", "St Martin De Londres"), StatusRow("Bearing", "062°"),
            StatusRow("Distance", "2.74 km"))),
        StatusPage("system", "System", emptyList()),
        StatusPage("task", "Task", emptyList()),
        StatusPage("rules", "Rules", emptyList()),
        StatusPage("times", "Times", emptyList()),
    ), listOf(43.79828, 3.78467))

    @Test
    fun flight() = captureRoboImage("src/test/screenshots/status_flight.png") {
        XcsTheme(dark = false) {
            StatusContent(fake, "flight", {}, { _, _ -> }, {})
        }
    }

    @Test
    fun timesDark() = captureRoboImage("src/test/screenshots/status_times_dark.png") {
        val times = StatusPage("times", "Times", listOf(
            StatusRow("Local time", "14:44"), StatusRow("UTC time", "12:44"),
            StatusRow("UTC date", "2026-10-10"), StatusRow("Flight time", "02:19"),
            StatusRow("Takeoff time", "12:25"), StatusRow("Landing time", ""),
            StatusRow("Daylight time", "07:55 - 18:58")))
        XcsTheme(dark = true) {
            StatusContent(StatusPages(fake.pages.dropLast(1) + times), "times", {},
                          { _, _ -> }, {})
        }
    }

    @Test
    fun shareText() {
        assertEquals("43.79828, 3.78467\nhttps://www.openstreetmap.org/" +
                         "?mlat=43.79828&mlon=3.78467#map=15/43.79828/3.78467",
                     shareText(43.798283, 3.784666))
    }
}
