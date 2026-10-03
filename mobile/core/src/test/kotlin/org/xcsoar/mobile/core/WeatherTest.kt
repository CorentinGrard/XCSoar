// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L3: the JSON of `xcs_weather_list` (core/host/CoreWeather.hpp). */
class WeatherTest {
    @Test
    fun parsesStations() {
        val list = WeatherStation.parseList("""
            [{"code":"LFMT","name":"Montpellier","qnh":1021.0,"wind_bearing":220.0,
              "wind_speed":4.1,"temperature":291.15,"dew_point":285.15,"visibility":9999,
              "cavok":true,"metar":"LFMT 031830Z 22008KT CAVOK 18/12 Q1021",
              "metar_time":"2026-10-03T18:30:00Z","text":"METAR for Montpellier:\n"},
             {"code":"EDDF"}]""")
        assertEquals(2, list.size)
        val lfmt = list[0]
        assertEquals("Montpellier", lfmt.name)
        assertEquals(1021.0, lfmt.qnh!!, 0.0)
        assertEquals(9999, lfmt.visibility)
        assertTrue(lfmt.cavok && lfmt.downloaded)
        // nothing downloaded yet: the code alone
        assertFalse(list[1].downloaded)
        assertNull(list[1].qnh)
    }

    @Test
    fun checksCodesLikeTheCore() {
        assertTrue(WeatherStation.isValidCode("LFMT"))
        assertTrue(WeatherStation.isValidCode("k1a2"))
        assertFalse(WeatherStation.isValidCode("LFM"))
        assertFalse(WeatherStation.isValidCode("LF-T"))
        assertFalse(WeatherStation.isValidCode("LFMTX"))
        assertFalse(WeatherStation.isValidCode("ÉDDF"))
    }

    @Test
    fun parsesRasp() {
        val rasp = RaspInfo.parse("""
            {"fields":[{"name":"wstar","label":"W*","help":"Thermal strength",
                        "times":["12:00","13:00"]},
                       {"name":"hbl","label":"H bl","times":["12:00"]}],
             "field":1,"time":null}""")
        assertEquals("H bl", rasp.selected?.label)
        assertNull(rasp.time)
        assertNull(rasp.fields[1].help)
        assertEquals(listOf("12:00", "13:00"), rasp.fields[0].times)
        // no file
        assertNull(RaspInfo.parse("""{"fields":[],"field":-1,"time":null}""").selected)
    }
}
