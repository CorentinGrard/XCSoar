// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.welcome

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.PlaneInfo
import org.xcsoar.mobile.core.RepositoryFile
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.UnitGroupInfo
import org.xcsoar.mobile.core.UnitInfo
import org.xcsoar.mobile.core.UnitPreset
import org.xcsoar.mobile.core.UnitSettings
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the first start's three steps (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class WelcomeScreenshotTest {
    private fun preset(name: String, distance: Int, altitude: Int, speed: Int, lift: Int) =
        UnitPreset(name, listOf(UnitGroupInfo(UnitGroup.DISTANCE.code, distance),
                                UnitGroupInfo(UnitGroup.ALTITUDE.code, altitude),
                                UnitGroupInfo(UnitGroup.HORIZONTAL_SPEED.code, speed),
                                UnitGroupInfo(UnitGroup.VERTICAL_SPEED.code, lift)))

    /* XCSoar's presets (src/Units/UnitsStore.cpp) */
    private val units = UnitSettings.METRIC.let { metric ->
        metric.copy(
            units = metric.units + listOf(
                UnitInfo(UnitInfo.STATUTE_MILES, "mi", 1 / 1609.344),
                UnitInfo(UnitInfo.FEET, "ft", 1 / 0.3048),
                UnitInfo(UnitInfo.KNOTS, "kt", 1.943844)),
            presets = listOf(
                preset("European", UnitInfo.KILOMETER, UnitInfo.METER,
                       UnitInfo.KILOMETER_PER_HOUR, UnitInfo.METER_PER_SECOND),
                preset("British", UnitInfo.KILOMETER, UnitInfo.FEET, UnitInfo.KNOTS,
                       UnitInfo.KNOTS),
                preset("American", UnitInfo.STATUTE_MILES, UnitInfo.FEET, UnitInfo.KNOTS,
                       UnitInfo.KNOTS),
                preset("Australian", UnitInfo.KILOMETER, UnitInfo.FEET, UnitInfo.KNOTS,
                       UnitInfo.KNOTS)),
            preset = 2)
    }

    @Test
    fun presetSamples() {
        assertEquals("km · m · km/h · m/s", presetSample(units, 0))
        // speed and lift both in knots: once
        assertEquals("mi · ft · kt", presetSample(units, 2))
    }

    @Test
    fun units() = captureRoboImage("src/test/screenshots/welcome_units.png") {
        XcsTheme(dark = false) {
            // an English phone in France: the core guessed American, the
            // phone's network says European
            WelcomeContent(WelcomeState(units = units, preset = 0))
        }
    }

    @Test
    fun crew() = captureRoboImage("src/test/screenshots/welcome_crew.png") {
        XcsTheme(dark = false) {
            WelcomeContent(WelcomeState(
                step = WelcomeStep.CREW, units = units, pilot = "Ada Lovelace",
                planes = listOf(PlaneInfo("/planes/D-1234.xcp", "D-1234", "AL",
                                          type = "LS 4")),
                plane = "/planes/D-1234.xcp"))
        }
    }

    @Test
    fun crewWithoutPlane() = captureRoboImage("src/test/screenshots/welcome_no_plane.png") {
        XcsTheme(dark = true) {
            WelcomeContent(WelcomeState(step = WelcomeStep.CREW, units = units,
                                        planes = emptyList()))
        }
    }

    private fun file(name: String, type: String, description: String) =
        RepositoryFile(name, "https://example.org/$name", type, "fr", description)

    private val files = listOf(
        RegionFileState(file("FRA_ALPS.xcm", "map", "Map of the French Alps"), progress = 0.4f),
        RegionFileState(file("FR-ASP-National-OpenAIP.txt", "airspace",
                             "France Airspace from OpenAIP"), done = true),
        RegionFileState(file("FR-WPT-National-XCSoar.cup", "waypoint", ""), chosen = false),
    )

    @Test
    fun data() = captureRoboImage("src/test/screenshots/welcome_data.png") {
        XcsTheme(dark = false) {
            WelcomeContent(WelcomeState(step = WelcomeStep.DATA, units = units,
                                        repository = files.map { it.file }, country = "fr",
                                        files = files))
        }
    }

    @Test
    fun dataOffline() = captureRoboImage("src/test/screenshots/welcome_offline.png") {
        XcsTheme(dark = false) {
            WelcomeContent(WelcomeState(step = WelcomeStep.DATA, units = units,
                                        repositoryError = "Unable to resolve host"))
        }
    }

    @Test
    fun countries() = captureRoboImage("src/test/screenshots/welcome_region.png") {
        XcsTheme(dark = false) {
            WelcomeContent(WelcomeState(
                step = WelcomeStep.DATA, units = units, country = "fr", choosingCountry = true,
                repository = listOf("fr", "de", "ch", "at", "it", "gb").map {
                    RepositoryFile("${it.uppercase()}-ASP-National-OpenAIP.txt", "x",
                                   "airspace", it)
                }))
        }
    }
}
