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
    fun mapOrientationsMatchHeader() {
        val c = CoreHeader.constants("XCS_MAP_")
        assertEquals(c, MapOrientation.entries.associate { "XCS_MAP_${it.name}" to it.code })
    }
}
