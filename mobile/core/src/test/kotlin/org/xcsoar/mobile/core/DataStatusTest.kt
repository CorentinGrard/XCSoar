// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DataStatusTest {
    @Test
    fun parsesCoreJson() {
        val status = DataStatus.parse(
            """{"map":{"files":["/data/XCSoarData/alps.xcm"],"terrain":true},""" +
                """"airspace":{"files":["/data/XCSoarData/fr.txt"],"count":812},""" +
                """"waypoints":{"files":[],"count":40},"future":1}""")

        assertEquals(listOf("/data/XCSoarData/alps.xcm"), status.map.files)
        assertEquals(true, status.map.terrain)
        assertEquals(812, status.airspace.count)
        assertEquals(emptyList<String>(), status.waypoints.files)
        assertEquals(40, status.waypoints.count)
    }

    @Test
    fun kindsMatchHeader() {
        val c = CoreHeader.constants("XCS_DATA_")
        assertEquals(c, DataFile.entries.associate { "XCS_DATA_${it.name}" to it.code })
    }

    @Test
    fun mapOptionsMatchHeader() {
        val c = CoreHeader.constants("XCS_MAP_")
            .filterKeys { MapOrientation.entries.none { o -> it == "XCS_MAP_${o.name}" } }
        assertEquals(c, MapOption.entries.associate { "XCS_MAP_${it.name}" to it.code })
    }

    @Test
    fun mapOrientationsMatchHeader() {
        val c = CoreHeader.constants("XCS_MAP_")
            .filterKeys { MapOption.entries.none { o -> it == "XCS_MAP_${o.name}" } }
        assertEquals(c, MapOrientation.entries.associate { "XCS_MAP_${it.name}" to it.code })
    }
}

class MapItemInfoTest {
    @Test
    fun parsesCoreJson() {
        val items = MapItemInfo.parseList(
            """[{"type":"airspace","name":"LF-R 46 N","class":"Restricted",""" +
                """"top":"FL95","base":"SFC"},""" +
                """{"type":"waypoint","name":"Anduze","landable":false,"elevation":136.0,""" +
                """"frequency":"123.500"},{"type":"location","elevation":646.5},""" +
                """{"type":"traffic"}]""")

        assertEquals(4, items.size)
        assertEquals("Restricted", items[0].`class`)
        assertEquals("FL95", items[0].top)
        assertEquals(false, items[1].landable)
        assertEquals("123.500", items[1].frequency)
        assertEquals(646.5, items[2].elevation!!, 0.0)
        assertEquals(null, items[3].name)
    }
}
