// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3: parsing `xcs_get_analysis`.  analysis.json is what the core wrote
 * after TestCoreApi's replay of test/data/01lz1hq1.igc
 * (`CORE_ANALYSIS_JSON=<path> output/UNIX/bin/TestCoreApi`).
 */
class AnalysisTest {
    private val replay by lazy {
        Analysis.parse(checkNotNull(javaClass.getResource("/analysis.json")).readText())
    }

    @Test
    fun parsesAWholeFlight() {
        val a = replay
        // landed: no flight time, no task
        assertNull(a.flightTime)
        assertNull(a.taskSpeed)
        assertTrue(a.legs.isEmpty())

        val b = a.barograph
        assertEquals(311, b.altitude.size)
        assertEquals(ChartPoint(0.0, 226.03738305588152), b.altitude.first())
        assertTrue(b.ceiling.isNotEmpty() && b.base.isNotEmpty())
        assertEquals(238.0, b.workingBand!![0], 0.1)
        assertEquals(2820.0, b.workingBand!![1], 0.1)
        assertNotNull(b.ceilingGradient)

        val c = a.climb
        assertEquals(1.5, c.macCready, 0.0)
        assertEquals(32, c.thermals.size)
        assertEquals(1.77, c.average!!, 0.01)
        assertEquals(c.trend!!.gradient, c.gradient!!, 0.0)

        val contest = a.contest
        assertEquals("WeGlide FREE", contest.name)
        assertEquals(1, contest.results.size)
        val r = contest.results.single()
        assertTrue(r.isDefined)
        // the contest solver works in time slices: it may differ a little
        assertEquals(521_155.0, r.distance, 521.0)
        assertEquals(7, r.points.size)
        assertEquals(473, contest.trace.size)
    }

    @Test
    fun otherPages() {
        val a = replay
        val polar = a.polar
        assertTrue(polar.isValid)
        assertEquals(1.5, polar.macCready, 0.0)
        assertEquals(25.9, polar.bestLd, 0.1)
        assertEquals(51, polar.polar.size)
        // the dolphin command starts at MacCready at the slowest speed
        assertEquals(XY(polar.vMin, 1.5), polar.dolphin.first())
        assertEquals(2, polar.bestGlide.size)

        val mc = a.macCready
        assertEquals(26, mc.speeds.size)
        assertEquals(MacCreadySpeeds(0.0, 26.558194415882436, 0.0), mc.speeds.first())
        assertEquals(polar.vBestLd, mc.vOpt, 0.0)

        val histogram = a.varioHistogram
        assertEquals(61, histogram.cruise.size)
        assertEquals(XY(-7.5, 0.85), histogram.circling.first())

        val band = a.thermalBand
        assertEquals(listOf(150.0, 2820.0), band.range!!.map { Math.round(it).toDouble() })
        assertEquals(53, band.collection.size)
        assertTrue(band.current.isEmpty())
        // landed: no altitude now, no risk MacCready
        assertNull(band.height)
        assertTrue(band.riskMacCready.isEmpty())

        // no wind measured for the time now (after the replay)
        assertTrue(a.wind.levels.isEmpty())
        assertTrue(a.temperature.levels.isEmpty())
        assertTrue(a.task.points.isEmpty())
    }

    @Test
    fun temperatureLevels() {
        val t = TemperatureTrace.serializer().let {
            kotlinx.serialization.json.Json.decodeFromString(it,
                """{"levels":[[0,20,18,null],[100,19,17.5,8]],"cloud_base":1500}""")
        }
        assertEquals(listOf(TemperatureLevel(0.0, 20.0, 18.0, null),
                            TemperatureLevel(100.0, 19.0, 17.5, 8.0)), t.levels)
        assertEquals(1500.0, t.cloudBase!!, 0.0)
    }

    @Test
    fun beforeAnyData() {
        val a = Analysis.parse("""{"legs":[],"barograph":{},"climb":{"mac_cready":1.0}}""")
        assertTrue(a.barograph.altitude.isEmpty())
        assertTrue(a.climb.thermals.isEmpty())
        assertNull(a.climb.average)
        assertTrue(a.contest.results.isEmpty())
    }

    @Test
    fun trendIsALine() {
        assertEquals(3.0, Trend(y0 = 1.0, gradient = 0.5).at(4.0), 0.0)
    }

    @Test
    fun parsesAirspaceCrossSection() {
        // as CoreAnalysis writes it; null when not moving
        val a = Analysis.parse("""{"airspace":{"range":5E4,"heights":[0,3300],"altitude":1450,
            "terrain":[300,null,350],"airspaces":[{"name":"CTR","class":"D","top":1981,
            "base":0,"fill":"#1F5BD6","border":"#1F5BD6","spans":[[8000,21000]]}],
            "glide":[[0,1450],[50000,620]]}}""")
        val xs = a.airspace!!
        assertEquals(50_000.0, xs.range, 0.0)
        assertEquals(null, xs.terrain[1])
        assertEquals("D", xs.airspaces.single().airspaceClass)
        assertEquals(null, Analysis.parse("""{"airspace":null}""").airspace)
    }
}
