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
 * after TestCoreApi's replay of test/data/01lz1hq1.igc.
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
        assertEquals(521_088.0, r.distance, 1.0)
        assertEquals(7, r.points.size)
        assertEquals(473, contest.trace.size)
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
}
