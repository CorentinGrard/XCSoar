// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** L3: the JSON of `xcs_tiles_*` (core/host/CoreInfoBoxes.hpp). */
class TilesTest {
    @Test
    fun parsesTypes() {
        val types = TileType.parseList("""[{"id":6,"name":"Speed ground","caption":"V GND",
            "description":"Ground speed measured by the GPS."}]""")
        assertEquals(TileType(6, "Speed ground", "V GND", "Ground speed measured by the GPS."),
                     types.single())
    }

    @Test
    fun parsesLayouts() {
        val l = TileLayouts.parse("""{"layouts":[[118,1,43,38,6,7],[21,22,117,118,1,25]]}""")
        assertEquals(listOf(118, 1, 43, 38, 6, 7), l.of(TileLayout.CRUISE))
        assertEquals(25, l.of(TileLayout.CIRCLING)[5])
    }

    @Test
    fun parsesValues() {
        // what the core wrote after a replay (TestCoreApi)
        val tiles = TileValue.parseList("""[{"type":43,"title":"Vopt","value":"142",
            "unit":"km/h","comment":"DOLPHIN","color":0,"comment_color":0},
            {"type":1,"title":"H AGL","value":"---","unit":"","comment":"","color":1,
             "comment_color":0}]""")
        assertEquals(TileValue(43, "Vopt", "142", "km/h", "DOLPHIN"), tiles[0])
        assertEquals(TileValue.COLOR_RED, tiles[1].color)
    }
}
