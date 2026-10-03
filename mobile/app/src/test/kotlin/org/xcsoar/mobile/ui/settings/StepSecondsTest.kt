// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The airspace warning and acknowledgement time steps. */
class StepSecondsTest {
    @Test
    fun up() {
        assertEquals(35, stepSeconds(30, up = true))
        assertEquals(75, stepSeconds(60, up = true))
        assertEquals(360, stepSeconds(300, up = true))
        assertEquals(1000, stepSeconds(990, up = true))
    }

    @Test
    fun down() {
        assertEquals(25, stepSeconds(30, up = false))
        assertEquals(55, stepSeconds(60, up = false))
        assertEquals(285, stepSeconds(300, up = false))
        assertEquals(300, stepSeconds(360, up = false))
        assertEquals(10, stepSeconds(10, up = false))
    }

    /** A value off the steps (from XCSoar's own dialog) lands on one. */
    @Test
    fun offTheSteps() {
        assertEquals(75, stepSeconds(62, up = true))
        assertEquals(60, stepSeconds(62, up = false))
    }
}
