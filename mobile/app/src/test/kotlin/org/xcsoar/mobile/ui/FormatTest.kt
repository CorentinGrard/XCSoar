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
}
