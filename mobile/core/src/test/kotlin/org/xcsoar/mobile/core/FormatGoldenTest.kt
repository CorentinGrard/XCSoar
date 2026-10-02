// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D9: [UnitFormatter] prints what XCSoar's Formatter prints, for every
 * case in format-golden.txt (written by core/test/FormatGolden.cpp).
 */
class FormatGoldenTest {
    private val lines = checkNotNull(javaClass.getResource("/format-golden.txt"))
        .readText().lines().filter { it.isNotBlank() }

    /** XCSoar's unit table, from the same file. */
    private val settings = UnitSettings(
        units = lines.filter { it.startsWith("unit ") }.map {
            val f = it.split(" ", limit = 5)
            UnitInfo(f[1].toInt(), f[4], f[2].toDouble(), f[3].toDouble())
        },
        groups = emptyList(),
    )

    @Test
    fun matchesXcsoar() {
        val format = UnitFormatter(settings)
        var checked = 0
        for (line in lines.filterNot { it.startsWith("unit ") }) {
            val f = line.split(" ", limit = 5)
            val unit = settings.unit(f[1].toInt())
            val value = f[2].toDouble()
            val flag = f[3] == "1"
            val actual = when (f[0]) {
                "altitude" -> format.altitude(value, unit)
                "relative_altitude" -> format.relativeAltitude(value, unit)
                "distance_smart" -> format.distanceSmart(value, unit).first
                "speed" -> format.speed(value, unit, flag)
                "vertical_speed" -> format.verticalSpeed(value, unit, flag)
                "wing_loading" -> format.wingLoading(value, unit)
                "mass" -> format.mass(value, unit)
                "temperature" -> format.temperature(value, unit)
                "pressure" -> format.pressure(value, unit)
                else -> error("unknown function in: $line")
            }
            assertEquals(line, f[4], actual)
            checked++
        }
        assertTrue(checked > 700)
    }

    @Test
    fun metricDefaultsMatchXcsoar() {
        for (unit in UnitSettings.METRIC.units) {
            val xcsoar = settings.unit(unit.unit)
            assertEquals(xcsoar.name, unit.name)
            assertEquals(xcsoar.factor, unit.factor, 1e-12)
            assertEquals(xcsoar.offset, unit.offset, 1e-9)
        }
    }
}
