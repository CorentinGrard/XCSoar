// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L3: the JSON of the NOTAM functions (core/host/CoreNotam.hpp). */
class NotamTest {
    @Test
    fun settingsRoundTrip() {
        val s = NotamSettings.parse("""
            {"enabled":true,"radius_km":100,"refresh_interval_min":0,
             "show_only_effective":false,"show_ifr":true,"max_radius_m":0,
             "hidden_qcodes":"QA"}""")
        assertEquals(NotamSettings(true, 100, 0, false, true, 0, "QA"), s)
        assertEquals(s, NotamSettings.parse(s.toJson()))
        assertTrue(s.toJson().contains("\"refresh_interval_min\":0"))
    }

    @Test
    fun parsesTheList() {
        val list = NotamList.parse("""
            {"loading":false,"updated":"2026-10-03T19:20:00Z","total":3,
             "notams":[{"number":"A1234/26","location":"LFMM","text":"PARACHUTING",
                        "start":"2026-10-03T08:00:00Z","end":"2026-10-03T18:00:00Z",
                        "permanent":false,"active":true,"lower":"SFC","upper":"FL115",
                        "distance":12000.0},
                       {"number":"B5/26","location":"LFMT","text":"CRANE",
                        "start":"2026-10-01T00:00:00Z","permanent":true,"active":true,
                        "lower":"","upper":""}]}""")
        assertEquals(3, list.total)
        assertEquals(2, list.notams.size)
        assertEquals("FL115", list.notams[0].upper)
        assertEquals(12000.0, list.notams[0].distance!!, 0.0)
        // permanent, no fix: no end, no distance
        assertNull(list.notams[1].end)
        assertNull(list.notams[1].distance)
        assertFalse(NotamList.parse("""{"total":0,"notams":[]}""").loading)
    }
}
