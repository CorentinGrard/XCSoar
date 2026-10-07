// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayBarTest {
    @Test
    fun nextSpeedGoesRoundTheChoices() {
        assertEquals(20.0, nextSpeed(10.0), 0.0)
        assertEquals(1.0, nextSpeed(50.0), 0.0)
        // a speed between the choices goes to the next one up
        assertEquals(5.0, nextSpeed(3.0), 0.0)
    }

    @Test
    fun speedText() {
        assertEquals("10", speedText(10.0))
        assertEquals("0.5", speedText(0.5))
    }

    @Test
    fun utcTimeOfDay() {
        // 2026-10-02 14:02:31 UTC
        assertEquals("14:02:31", utcTime(1_790_949_751.0))
        assertEquals("00:00:00", utcTime(86_400.0))
    }
}
