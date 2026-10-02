// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun duration() {
        assertEquals("0:00", Format.duration(0.0).text)
        assertEquals("0:59", Format.duration(59 * 60 + 59.9).text)
        assertEquals("2:05", Format.duration(2 * 3600 + 5 * 60.0).text)
        assertEquals("12:00", Format.duration(12 * 3600.0).text)
        assertEquals(Format.INVALID, Format.duration(null).text)
        assertEquals(Format.INVALID, Format.duration(-1.0).text)
    }

    @Test
    fun minutesSeconds() {
        assertEquals("3:24", Format.minutesSeconds(204.4).text)
        assertEquals("0:05", Format.minutesSeconds(5.0).text)
        assertEquals("61:00", Format.minutesSeconds(3660.0).text)
        assertEquals(Format.INVALID, Format.minutesSeconds(null).text)
    }

    @Test
    fun glideRatio() {
        assertEquals("27.4", Format.glideRatio(27.43).text)
        assertEquals("120", Format.glideRatio(120.4).text)
        assertEquals("0.0", Format.glideRatio(0.0).text)
        assertEquals(Format.INVALID, Format.glideRatio(null).text)
        assertEquals("+++", Format.requiredGlideRatio(0.0).text)
        assertEquals("27.4", Format.requiredGlideRatio(27.43).text)
    }

    @Test
    fun polar() {
        assertEquals("120", Format.ballast(119.6).text)
        assertEquals("0", Format.bugs(1.0).text)
        assertEquals("15", Format.bugs(0.85).text)
        assertEquals("50", Format.bugs(0.5).text)
        assertEquals("32.5", Format.wingLoading(32.46).text)
        assertEquals(Format.INVALID, Format.wingLoading(null).text)
    }
}
