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
                """{"type":"waypoint","id":42,"name":"Anduze","landable":false,"elevation":136.0,""" +
                """"frequency":"123.500"},{"type":"location","elevation":646.5},""" +
                """{"type":"traffic"}]""")

        assertEquals(4, items.size)
        assertEquals("Restricted", items[0].`class`)
        assertEquals("FL95", items[0].top)
        assertEquals(false, items[1].landable)
        assertEquals(42, items[1].id)
        assertEquals("123.500", items[1].frequency)
        assertEquals(646.5, items[2].elevation!!, 0.0)
        assertEquals(null, items[3].name)
    }
}

class RepositoryFileTest {
    @Test
    fun parsesCoreJson() {
        val files = RepositoryFile.parseList(
            """[{"name":"FRA_FULL.xcm","uri":"http://x/FRA_FULL.xcm","type":"map",""" +
                """"area":"fr","description":"France","updated":"2026-05-01","folder":"maps",""" +
                """"sha256":"ab"},{"name":"x.dat","uri":"http://x/x.dat","type":"other"}]""")

        assertEquals(DataFile.MAP, files[0].dataFile)
        assertEquals("maps", files[0].folder)
        assertEquals("ab", files[0].sha256)
        assertEquals(null, files[1].dataFile)
        assertEquals("", files[1].area)
    }
}

class AirspaceWarningInfoTest {
    @Test
    fun parsesCoreJson() {
        val w = AirspaceWarningInfo.parseList(
            """[{"id":"0x7c3a","state":"inside","name":"LF-R 46 N","class":"Restricted",""" +
                """"top":"FL95","base":"SFC"},{"id":"0x1","state":"near","name":"CTA",""" +
                """"distance":1200.0,"time":85.0}]""")

        assertEquals(true, w[0].inside)
        assertEquals("FL95", w[0].top)
        assertEquals(null, w[0].time)
        assertEquals(false, w[1].inside)
        assertEquals(85.0, w[1].time!!, 0.0)
    }
}
