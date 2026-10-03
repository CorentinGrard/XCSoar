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
        // like XCSoar: decimals only up to 20
        assertEquals("32", Format.wingLoading(32.46).text)
        assertEquals("18.3", Format.wingLoading(18.26).text)
        assertEquals(Format.INVALID, Format.wingLoading(null).text)
    }

    @Test
    fun followsUnits() {
        val metric = Format.units
        try {
            Format.units = metric.copy(
                units = metric.units + listOf(
                    org.xcsoar.mobile.core.UnitInfo(10, "ft", 3.2808399),
                    org.xcsoar.mobile.core.UnitInfo(5, "kt", 1.94384449)),
                groups = metric.groups.map {
                    when (it.group) {
                        org.xcsoar.mobile.core.UnitGroup.ALTITUDE.code -> it.copy(unit = 10)
                        org.xcsoar.mobile.core.UnitGroup.VERTICAL_SPEED.code -> it.copy(unit = 5)
                        else -> it
                    }
                })
            assertEquals(Format.Value("3281", "ft"), Format.altitude(1000.0))
            assertEquals(Format.Value("+3.9", "kt"), Format.vario(2.0))
            assertEquals(Format.Value("0.0", "kt"), Format.vario(-0.01))
            // MacCready steps of 0.2 kt, on that grid
            assertEquals(1.2, org.xcsoar.mobile.core.UnitInfo(5, "kt", 1.94384449)
                .toUser(Format.stepVerticalSpeed(1.0 / 1.94384449, +1)), 1e-9)
        } finally {
            Format.units = metric
        }
    }

    @Test
    fun pressure() {
        assertEquals(Format.Value("1013", "hPa"), Format.pressure(1013.25))
        assertEquals(Format.Value(Format.INVALID, "hPa"), Format.pressure(null))
        // from the standard atmosphere, the first step lands on the grid
        assertEquals(1014.0, Format.stepPressure(1013.25, +1), 1e-9)
        assertEquals(1012.0, Format.stepPressure(1013.25, -1), 1e-9)
    }

    @Test
    fun pressureInInchesOfMercury() {
        val metric = Format.units
        val inHg = org.xcsoar.mobile.core.UnitInfo(
            org.xcsoar.mobile.core.UnitInfo.INCH_MERCURY, "inHg", 0.0295287441401431)
        try {
            Format.units = metric.copy(
                units = metric.units + inHg,
                groups = metric.groups.map {
                    if (it.group == org.xcsoar.mobile.core.UnitGroup.PRESSURE.code)
                        it.copy(unit = inHg.unit) else it
                })
            assertEquals(Format.Value("29.92", "inHg"), Format.pressure(1013.25))
            assertEquals(29.93, inHg.toUser(Format.stepPressure(1013.25, +1)), 1e-9)
        } finally {
            Format.units = metric
        }
    }

    @Test
    fun distanceLikeXcsoar() {
        assertEquals(Format.Value("7.30", "km"), Format.distance(7300.0))
        assertEquals(Format.Value("23.4", "km"), Format.distance(23_400.0))
        assertEquals(Format.Value("123", "km"), Format.distance(123_400.0))
    }
}
