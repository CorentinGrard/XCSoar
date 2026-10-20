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

    @Test
    fun parsesGraphics() {
        // as TestCoreApi checks them: a spark, the barogram, an arrow
        val tiles = TileValue.parseList("""[
            {"type":24,"title":"Vario Trace","value":"","unit":"","comment":"+1.2",
             "color":0,"comment_color":0,"graphic":{"kind":"spark","values":[0.5,-1.0],
             "capacity":30,"center":true,"mac_cready":1.5,"scale":[-2.0,3.0]}},
            {"type":79,"title":"Barogram","value":"","unit":"","comment":"","color":0,
             "comment_color":0,"graphic":{"kind":"barogram","points":[[0.1,500.0],
             [0.2,800.0]],"terrain":[[0.1,300.0]]}},
            {"type":46,"title":"Wind","value":"","unit":"","comment":"","color":0,
             "comment_color":0,"graphic":{"kind":"wind_arrow","angle":90.0}},
            {"type":6,"title":"V GND","value":"118","unit":"km/h","comment":"","color":0,
             "comment_color":0,"graphic":null}]""")
        val spark = tiles[0].graphic!!
        assertEquals(TileGraphic.SPARK, spark.kind)
        assertEquals(listOf(0.5, -1.0), spark.values)
        assertEquals(30, spark.capacity)
        assertEquals(true, spark.center)
        assertEquals(1.5, spark.macCready, 0.0)
        assertEquals(listOf(-2.0, 3.0), spark.scale)
        assertEquals(listOf(ChartPoint(0.1, 500.0), ChartPoint(0.2, 800.0)),
                     tiles[1].graphic!!.points)
        assertEquals(listOf(ChartPoint(0.1, 300.0)), tiles[1].graphic!!.terrain)
        assertEquals(TileGraphic(TileGraphic.WIND_ARROW, angle = 90.0), tiles[2].graphic)
        assertEquals(null, tiles[3].graphic)
    }

    @Test
    fun tileActions() {
        // as core/host/CoreInfoBoxes.hpp documents them
        assertEquals(TileAction("analysis", "thermal_band"),
                     TileAction.parse("""{"action":"analysis","page":"thermal_band"}"""))
        assertEquals(TileAction("wind"), TileAction.parse("""{"action":"wind"}"""))
    }
}
