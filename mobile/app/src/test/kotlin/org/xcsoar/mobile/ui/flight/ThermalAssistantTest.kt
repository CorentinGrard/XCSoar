// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.xcsoar.mobile.core.TileGraphic

/** Where the thermal assistant puts the lift (ThermalAssistantRenderer's geometry). */
class ThermalAssistantTest {
    /* lift only on one heading */
    private fun onlyOn(heading: Int, left: Boolean) = TileGraphic(
        TileGraphic.THERMAL_ASSISTANT, circling = true, left = left,
        lift = (0 until 36).map { if (it * 10 == heading) 2.0 else 0.0 })

    private fun strongestPoint(g: TileGraphic, up: Double): Offset {
        val points = liftPoints(g, Offset(0f, 0f), 100f, up)!!
        return points.maxBy { it.getDistance() }
    }

    @Test
    fun liftWhereTheGliderWasOnTheCircle() {
        // turning left on a heading of north, the glider is east of the centre
        val east = strongestPoint(onlyOn(0, left = true), up = 0.0)
        assertEquals(100f, east.x, 0.01f)
        assertEquals(0f, east.y, 0.01f)
        assertEquals(90.0, strongestBearing(onlyOn(0, left = true))!!, 1e-9)
        // turning right, west
        assertEquals(-100f, strongestPoint(onlyOn(0, left = false), up = 0.0).x, 0.01f)
        assertEquals(270.0, strongestBearing(onlyOn(0, left = false))!!, 1e-9)
    }

    @Test
    fun turnsWithTheMap() {
        // east of the centre on a map whose top is east: straight up
        val up = strongestPoint(onlyOn(0, left = true), up = 90.0)
        assertEquals(0f, up.x, 0.01f)
        assertEquals(-100f, up.y, 0.01f)
    }

    @Test
    fun noLiftNoPoints() {
        assertNull(liftPoints(TileGraphic(TileGraphic.THERMAL_ASSISTANT), Offset.Zero, 100f, 0.0))
        assertNull(strongestBearing(TileGraphic(TileGraphic.THERMAL_ASSISTANT,
                                                lift = List(36) { 0.0 })))
        // no climb: every point halfway, on the inner circle
        val calm = liftPoints(TileGraphic(TileGraphic.THERMAL_ASSISTANT, lift = List(36) { 0.0 }),
                              Offset.Zero, 100f, 0.0)!!
        calm.forEach { assertEquals(50f, it.getDistance(), 0.01f) }
    }
}
