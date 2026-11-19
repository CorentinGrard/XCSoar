// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionFilesTest {
    /* a few files as XCSoar's repository has them (2026-11) */
    private fun file(name: String, type: String, area: String, vararg bbox: Double) =
        RepositoryFile(name, "https://example.org/$name", type, area,
                       bbox = bbox.toList().takeIf { it.size == 4 })

    private val files = listOf(
        file("ALPS.xcm", "map", "", 4.5, 43.4, 16.5, 49.0),
        file("ALPS_HighRes.xcm", "map", "", 4.5, 43.4, 16.5, 49.0),
        file("FRA_ALPS.xcm", "map", "fr", 3.5, 43.5, 9.0, 47.5),
        file("FRA_FULL.xcm", "map", "fr", -5.0, 42.0, 9.0, 51.5),
        file("FRA_FULL_HighRes.xcm", "map", "fr", -5.0, 42.0, 9.0, 51.5),
        file("GER.xcm", "map", "de", 5.5, 47.0, 15.5, 55.0),
        file("FR-ASP-National-OpenAIP.txt", "airspace", "fr", -8.0, 39.0, 10.7, 51.18),
        file("FR-ASP-National-PlaneurNet.txt", "airspace", "fr"),
        file("FR-WPT-Airports-OpenAIP.cupx", "waypoint", "fr", -5.06, 41.37, 9.54, 51.04),
        file("FR-WPT-National-XCSoar.cup", "waypoint", "fr", -61.5, -0.35, 55.7, 51.2),
        file("DE-ASP-National-OpenAIP.txt", "airspace", "de", 3.76, 47.27, 15.0, 55.0),
        file("DE-WPT-Airports-OpenAIP.cupx", "waypoint", "DE", 5.93, 47.41, 15.0, 55.2),
        file("NZ-ASP-National-OpenAIP.txt", "airspace", "nz", 165.0, -48.0, -175.0, -33.0),
    )

    private val grenoble = GeoPosition(45.19, 5.73)
    private val berlin = GeoPosition(52.52, 13.40)
    private val strasbourg = GeoPosition(48.58, 7.75)

    @Test
    fun parsesTheBoundingBox() {
        val parsed = RepositoryFile.parseList(
            """[{"name":"FRA_FULL.xcm","uri":"http://x/FRA_FULL.xcm","type":"map",""" +
                """"area":"fr","bbox":[-5E0,4.2E1,9E0,5.15E1]},""" +
                """{"name":"a.txt","uri":"http://x/a.txt","type":"airspace"}]""")
        assertEquals(listOf(-5.0, 42.0, 9.0, 51.5), parsed[0].bbox)
        assertTrue(parsed[0].covers(grenoble))
        assertFalse(parsed[0].covers(berlin))
        assertNull(parsed[1].bbox)
        assertFalse(parsed[1].covers(grenoble))
    }

    @Test
    fun coversAcrossTheAntimeridian() {
        val nz = files.last()
        assertTrue(nz.covers(GeoPosition(-41.3, 174.8)))   // Wellington
        assertTrue(nz.covers(GeoPosition(-44.0, -176.5)))  // Chatham Islands
        assertFalse(nz.covers(GeoPosition(-41.3, 100.0)))
    }

    @Test
    fun countryFromThePhoneWhenItsFilesCoverThePosition() {
        // a French SIM in Strasbourg: France, though Germany's box fits closer
        assertEquals("fr", RegionFiles.country(files, strasbourg, listOf("FR", "us")))
        // an American phone in Grenoble without a SIM: the closest box
        assertEquals("fr", RegionFiles.country(files, grenoble, listOf("US")))
        assertEquals("de", RegionFiles.country(files, berlin, listOf("fr")))
    }

    @Test
    fun countryWithoutAPosition() {
        assertEquals("de", RegionFiles.country(files, null, listOf("us", "DE")))
        assertNull(RegionFiles.country(files, null, listOf("us")))
        assertNull(RegionFiles.country(files, GeoPosition(0.0, -150.0), listOf("us")))
    }

    @Test
    fun suggestsTheClosestMapAndTheNationalFiles() {
        val here = RegionFiles.suggest(files, "fr", grenoble)
        assertEquals("FRA_ALPS.xcm", here.map?.name)
        assertEquals("FR-ASP-National-OpenAIP.txt", here.airspace?.name)
        assertEquals("FR-WPT-National-XCSoar.cup", here.waypoints?.name)
        assertEquals(3, here.files.size)

        // the region chosen by hand, elsewhere: the country's largest map
        assertEquals("FRA_FULL.xcm", RegionFiles.suggest(files, "FR", berlin).map?.name)
        assertEquals("FRA_FULL.xcm", RegionFiles.suggest(files, "fr", null).map?.name)
    }

    @Test
    fun fallsBackToOpenAipAirports() {
        val de = RegionFiles.suggest(files, "de", berlin)
        assertEquals("GER.xcm", de.map?.name)
        assertEquals("DE-ASP-National-OpenAIP.txt", de.airspace?.name)
        assertEquals("DE-WPT-Airports-OpenAIP.cupx", de.waypoints?.name)
        assertEquals(listOf("de", "fr", "nz"), RegionFiles.countries(files))
    }

    @Test
    fun presetUnits() {
        val units = UnitSettings.METRIC.copy(presets = listOf(
            UnitPreset("European", listOf(UnitGroupInfo(UnitGroup.ALTITUDE.code, UnitInfo.METER))),
            UnitPreset("Old core")))
        assertEquals("m", units.presetUnit(0, UnitGroup.ALTITUDE)?.name)
        assertNull(units.presetUnit(0, UnitGroup.DISTANCE))
        assertNull(units.presetUnit(1, UnitGroup.ALTITUDE))
        assertNull(units.presetUnit(2, UnitGroup.ALTITUDE))
    }
}
