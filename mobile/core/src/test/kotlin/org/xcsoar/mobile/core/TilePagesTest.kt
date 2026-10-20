// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L3: the JSON of the tile pages (core/host/CoreTilePages.hpp). */
class TilePagesTest {
    @Test
    fun parsesTeamCode() {
        val info = TeamCodeInfo.parse("""{"own":"4A2C","mate":"8B1D","range":"12.4 km",
            "bearing":"238°","relative_bearing":"<14°","flarm_lock":"",
            "reference":{"id":3,"name":"Benalla"}}""")
        assertEquals(WaypointRef(3, "Benalla"), info.reference)
        assertEquals("<14°", info.relativeBearing)
        assertNull(TeamCodeInfo.parse("""{"own":"","mate":""}""").reference)
    }

    @Test
    fun parsesRadio() {
        val radio = RadioInfo.parse("""{"active":{"frequency":"122.500","name":"Tower"},
            "standby":{"frequency":"","name":""}}""")
        assertEquals(RadioChannel("122.500", "Tower"), radio[RadioSlot.ACTIVE])
        assertEquals("", radio[RadioSlot.STANDBY].frequency)
    }

    @Test
    fun parsesAtc() {
        assertEquals(AtcInfo("Benalla", "36°33'S 146°00'E", -2.0),
                     AtcInfo.parse("""{"declination":-2E0,"waypoint":"Benalla",
                         "location":"36°33'S 146°00'E"}"""))
        assertNull(AtcInfo.parse("""{"declination":0,"waypoint":""}""").location)
    }

    @Test
    fun parsesAirspace() {
        assertNull(AirspaceDetails.parse("{}"))
        val details = AirspaceDetails.parse("""{"name":"CTR Grenoble","class":"D",
            "type":"CTR","top":"1981 m","base":"GND","frequency":"120.505",
            "station":"","squawk":"","distance":"","acknowledged":true}""")
        assertEquals("D", details?.airspaceClass)
        assertEquals(true, details?.acknowledged)
    }
}
