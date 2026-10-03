// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.crew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.xcsoar.mobile.core.WeGlideAircraft

/** Matching WeGlide's model names with XCSoar's built-in polars. */
class AircraftModelsTest {
    /* names as PolarStore.cpp has them */
    private val polars = listOf("ASG-29 (18m)", "ASG-29E (18m)", "Discus 2c (18m)", "Ka 6 CR",
                                "LS-1c", "LS-10s (18m)", "LS-4", "DG-101 G", "Duo Discus T",
                                "303 Mosquito", "401 Kestrel (17m)")

    private fun match(name: String) = matchPolar(name, polars)?.let { polars[it] }

    @Test
    fun sameNameWithoutPunctuation() {
        assertEquals("LS-4", match("LS 4"))
        assertEquals("ASG-29 (18m)", match("ASG 29 18m"))
        assertEquals("Ka 6 CR", match("Ka 6 CR"))
    }

    @Test
    fun closestVariant() {
        // the span is a variant; the plain model before the "E"
        assertEquals("ASG-29 (18m)", match("ASG 29"))
        assertEquals("Discus 2c (18m)", match("Discus 2c"))
        assertEquals("LS-1c", match("LS 1"))
        // WeGlide leaves out the model number
        assertEquals("303 Mosquito", match("Mosquito"))
        assertEquals("401 Kestrel (17m)", match("Kestrel 17m"))
    }

    @Test
    fun noWrongModel() {
        // LS 1 is not LS 10, a DG-1000 is not a DG-101
        assertEquals("LS-1c", match("LS-1"))
        assertNull(match("DG 1000"))
        assertNull(match("Arcus M"))
        assertNull(match(""))
    }

    @Test
    fun modelsJoinBothLists() {
        val weGlide = listOf(WeGlideAircraft(1, "LS 4"), WeGlideAircraft(2, "Arcus M"))
        val models = aircraftModels(weGlide, listOf("LS-4", "Ka 6 CR"))
        assertEquals(listOf("Arcus M", "Ka 6 CR", "LS 4"), models.map { it.name })
        assertEquals(0, models.first { it.name == "LS 4" }.polar)
        assertNull(models.first { it.name == "Arcus M" }.polar)
        assertNull(models.first { it.name == "Ka 6 CR" }.weGlide)
        assertEquals(listOf("LS 4"), models.search("ls-4").map { it.name })
    }
}
