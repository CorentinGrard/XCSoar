// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L3: the JSON of `xcs_tracking_get` / `xcs_tracking_set`. */
class TrackingTest {
    /* as core/host/CoreTracking.cpp writes it */
    private val core = """
        {"skylines":{"enabled":true,"roaming":true,"interval":10,"traffic":false,
                     "near_traffic":true,"key":"ABCDEF0123"},
         "livetrack24":{"enabled":false,"server":"livexc.dhv.de","username":"pilot",
                        "password":"secret","interval":60,"vehicle_type":0,
                        "vehicle_name":"LS 4"},
         "cloud":{"enabled":null,"show_traffic":true,"show_thermals":false,"roaming":true}}"""

    @Test
    fun parses() {
        val s = TrackingSettings.parse(core)
        assertEquals(TrackingSettings.SkyLines(true, true, 10, false, true, "ABCDEF0123"),
                     s.skylines)
        assertEquals("livexc.dhv.de", s.livetrack24.server)
        assertEquals("LS 4", s.livetrack24.vehicleName)
        assertNull(s.cloud.enabled)
        assertFalse(s.cloud.showThermals)
    }

    /** What the app sends back reads the same; "not asked yet" is left out. */
    @Test
    fun roundTrips() {
        val s = TrackingSettings.parse(core)
        val json = s.toJson()
        assertFalse(json.contains("null"))
        assertTrue(json.contains("\"near_traffic\":true"))
        assertEquals(s, TrackingSettings.parse(json))
        val on = s.copy(cloud = s.cloud.copy(enabled = true))
        assertTrue(on.toJson().contains("\"cloud\":{\"enabled\":true"))
    }

    @Test
    fun checksWhatTheCoreRefuses() {
        assertTrue(TrackingSettings.isValidKey("0"))
        assertTrue(TrackingSettings.isValidKey("abcDEF0123456789"))
        assertFalse(TrackingSettings.isValidKey("XYZ"))
        assertFalse(TrackingSettings.isValidKey("0123456789ABCDEF0"))
        assertTrue(TrackingSettings.fits("é".repeat(31)))
        // 64 bytes of UTF-8, although 32 characters
        assertFalse(TrackingSettings.fits("é".repeat(32)))
    }
}
