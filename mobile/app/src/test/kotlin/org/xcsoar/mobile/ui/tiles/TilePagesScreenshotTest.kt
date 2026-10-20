// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.tiles

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.AirspaceDetails
import org.xcsoar.mobile.core.AtcInfo
import org.xcsoar.mobile.core.RadioChannel
import org.xcsoar.mobile.core.RadioInfo
import org.xcsoar.mobile.core.TeamCodeInfo
import org.xcsoar.mobile.core.WaypointRef
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the pages of the team code, radio, ATC and airspace tiles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class TilePagesScreenshotTest {
    @Test
    fun teamCode() = captureRoboImage("src/test/screenshots/team_code.png") {
        XcsTheme(dark = false) {
            TeamCodeContent(TeamCodeInfo(own = "4A2C", mate = "8B1D",
                                         reference = WaypointRef(1, "Grenoble Le Versoud"),
                                         range = "12.4 km", bearing = "238°",
                                         relativeBearing = "<14°"),
                            { true }, {}, {})
        }
    }

    @Test
    fun radioDark() = captureRoboImage("src/test/screenshots/radio_dark.png") {
        XcsTheme(dark = true) {
            RadioContent(RadioInfo(RadioChannel("120.505", "Grenoble Info"), RadioChannel()),
                         { _, _ -> }, { _, _ -> true }, {})
        }
    }

    @Test
    fun atc() = captureRoboImage("src/test/screenshots/atc.png") {
        XcsTheme(dark = false) {
            AtcContent(AtcInfo("Grenoble Le Versoud", "45°13'02\" N 005°50'59\" E", 2.0),
                       {}, {}, {}, {})
        }
    }

    @Test
    fun airspace() = captureRoboImage("src/test/screenshots/airspace_details.png") {
        XcsTheme(dark = false) {
            AirspaceDetailsContent(
                AirspaceDetailsState(read = true, details = AirspaceDetails(
                    name = "CTR Grenoble", airspaceClass = "D", type = "CTR",
                    top = "1981 m", base = "GND", frequency = "120.505",
                    station = "Grenoble Info", distance = "3.2 km")),
                {}, {}, {})
        }
    }

    @Test
    fun teamCodes() {
        assertTrue(TeamCodeViewModel.isTeamCode(""))
        assertTrue(TeamCodeViewModel.isTeamCode("AB12"))
        assertFalse(TeamCodeViewModel.isTeamCode("AB 12"))
        assertFalse(TeamCodeViewModel.isTeamCode("ABCDEFGH"))
    }

    @Test
    fun radioChannels() {
        assertTrue(RadioViewModel.isChannel("122.5"))
        assertTrue(RadioViewModel.isChannel("118.015"))
        assertTrue(RadioViewModel.isChannel("136.990"))
        assertFalse(RadioViewModel.isChannel("118.020"))
        assertFalse(RadioViewModel.isChannel("137"))
        assertFalse(RadioViewModel.isChannel("117.995"))
        assertFalse(RadioViewModel.isChannel("122.512"))
        assertFalse(RadioViewModel.isChannel("abc"))
    }
}
