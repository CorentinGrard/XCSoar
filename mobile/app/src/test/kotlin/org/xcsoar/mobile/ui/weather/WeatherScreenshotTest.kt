// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.weather

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.RaspField
import org.xcsoar.mobile.core.RaspInfo
import org.xcsoar.mobile.core.WeatherStation
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the weather stations (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class WeatherScreenshotTest {
    private val lfmt = WeatherStation(
        "LFMT", name = "Montpellier", metar = "LFMT 031830Z 22008KT CAVOK 18/12 Q1021 NOSIG",
        metarTime = "2026-10-03T18:30:00Z", taf = "TAF LFMT 031700Z 0318/0418 22010KT CAVOK",
        tafTime = "2026-10-03T17:00:00Z", qnh = 1021.0, windBearing = 220.0, windSpeed = 4.1,
        temperature = 291.15, dewPoint = 285.15, visibility = 9999, cavok = true,
        text = "METAR for Montpellier:\n\nWind: 220° 15 km/h\nTemperature: 18 °C\n" +
            "Dew Point: 12 °C\nPressure: 1021 hPa\n\n" +
            "LFMT 031830Z 22008KT CAVOK 18/12 Q1021 NOSIG\n\n" +
            "TAF LFMT 031700Z 0318/0418 22010KT CAVOK")

    private val rasp = RaspInfo(
        fields = listOf(
            RaspField("wstar", "W*", "Average dry thermal updraft strength near mid-BL height.",
                      listOf("10:00", "12:00", "14:00", "16:00")),
            RaspField("blwindspd", "BL Wind spd", times = listOf("12:00")),
            RaspField("hbl", "H bl", times = listOf("12:00")),
            RaspField("blcloudpct", "bl cloud", times = listOf("12:00"))),
        field = 0, time = "14:00")

    /** Tall enough for the forecast and the stations. */
    @Test
    @Config(qualifiers = "w390dp-h1400dp-xxhdpi")
    fun stations() = captureRoboImage("src/test/screenshots/weather.png") {
        XcsTheme(dark = false) {
            WeatherContent(
                WeatherState(stations = listOf(lfmt, WeatherStation("LFNH")), code = "LFMV",
                             rasp = rasp,
                             raspFile = "/d/weather/rasp/FR-RASP-National-ThermalMap.dat"),
                {}, {}, {}, {}, {}, initiallyExpanded = "LFMT")
        }
    }

    @Test
    fun empty() = captureRoboImage("src/test/screenshots/weather_empty.png") {
        XcsTheme(dark = false) {
            WeatherContent(WeatherState(), {}, {}, {}, {}, {})
        }
    }
}
