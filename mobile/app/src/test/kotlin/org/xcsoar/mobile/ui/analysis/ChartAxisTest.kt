// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartAxisTest {
    @Test
    fun niceStep() {
        assertEquals(500.0, ChartAxis.niceStep(2400.0), 1e-9)
        assertEquals(1000.0, ChartAxis.niceStep(2800.0), 1e-9)
        assertEquals(2000.0, ChartAxis.niceStep(9000.0), 1e-9)
        assertEquals(1.0, ChartAxis.niceStep(4.2), 1e-9)
        assertEquals(0.2, ChartAxis.niceStep(0.9), 1e-9)
        assertEquals(1.0, ChartAxis.niceStep(0.0), 0.0)
    }

    @Test
    fun timeStep() {
        assertEquals(0.25, ChartAxis.timeStep(0.25), 0.0)
        assertEquals(0.5, ChartAxis.timeStep(2.5), 0.0)
        assertEquals(1.0, ChartAxis.timeStep(5.2), 0.0)
        assertEquals(2.0, ChartAxis.timeStep(9.0), 0.0)
    }

    @Test
    fun label() {
        assertEquals("500", ChartAxis.label(500.0, 500.0))
        assertEquals("0.4", ChartAxis.label(0.4000000001, 0.2))
        assertEquals("0.0", ChartAxis.label(-1e-12, 0.2))
        assertEquals("-1.5", ChartAxis.label(-1.5, 0.5))
    }
}
