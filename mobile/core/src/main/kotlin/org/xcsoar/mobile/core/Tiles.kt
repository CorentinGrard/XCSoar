// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** The flight screen's two sets of tiles (`XCS_TILES_*`). */
enum class TileLayout(val code: Int) {
    CRUISE(0),
    CIRCLING(1),
}

/** One of XCSoar's InfoBox types a tile can show (`xcs_tiles_types`). */
@Serializable
data class TileType(
    val id: Int,
    /** XCSoar's name, e.g. "Speed ground". */
    val name: String,
    /** The tile's title, e.g. "V GND". */
    val caption: String = "",
    val description: String = "",
) {
    companion object {
        fun parseList(text: String): List<TileType> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}

/** The InfoBox types of each layout's tiles (`xcs_tiles_layouts`). */
@Serializable
data class TileLayouts(val layouts: List<List<Int>> = emptyList()) {
    fun of(layout: TileLayout): List<Int> = layouts.getOrNull(layout.code).orEmpty()

    companion object {
        fun parse(text: String): TileLayouts = json.decodeFromString(serializer(), text)
    }
}

/** What one tile shows, as XCSoar's InfoBox does (`xcs_tiles_update`). */
@Serializable
data class TileValue(
    val type: Int,
    val title: String,
    /** Formatted in the pilot's units; "---" when invalid. */
    val value: String,
    val unit: String = "",
    val comment: String = "",
    /** InfoBoxLook's colours: 0 none, 1 red, 2 blue, 3 green, 4 yellow, 5 magenta. */
    val color: Int = 0,
    @SerialName("comment_color") val commentColor: Int = 0,
    /** What a graphical InfoBox draws in place of its value; null for
        the others, and when it has nothing to draw. */
    val graphic: TileGraphic? = null,
) {
    companion object {
        const val COLOR_NONE = 0
        const val COLOR_RED = 1
        const val COLOR_BLUE = 2
        const val COLOR_GREEN = 3
        const val COLOR_YELLOW = 4
        const val COLOR_MAGENTA = 5

        fun parseList(text: String): List<TileValue> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}

/**
 * A graphical InfoBox's drawing, as data ([kind]):
 *  - "spark": the last [values] of a vario (m/s, oldest first) on a
 *    chart [capacity] values wide, [center]ed on zero or not, with the
 *    [macCready] line; [scale] is the vario gauge's range (negative,
 *    positive) the chart spans at least.
 *  - "barogram": the flight's altitude and the terrain under it
 *    ([ChartPoint]s, hours and m).
 *  - "wind_arrow", "next_arrow": where the wind blows to, relative to
 *    the heading, or where the next waypoint is, relative to the track
 *    ([angle], degrees clockwise).
 */
@Serializable
data class TileGraphic(
    val kind: String,
    val values: List<Double> = emptyList(),
    val capacity: Int = 0,
    val center: Boolean = false,
    @SerialName("mac_cready") val macCready: Double = 0.0,
    val scale: List<Double> = emptyList(),
    @SerialName("points") private val pointsRaw: List<List<Double>> = emptyList(),
    @SerialName("terrain") private val terrainRaw: List<List<Double>> = emptyList(),
    val angle: Double = 0.0,
) {
    val points get() = pointsRaw.map { ChartPoint(it[0], it[1]) }
    val terrain get() = terrainRaw.map { ChartPoint(it[0], it[1]) }

    companion object {
        const val SPARK = "spark"
        const val BAROGRAM = "barogram"
        const val WIND_ARROW = "wind_arrow"
        const val NEXT_ARROW = "next_arrow"
    }
}

/**
 * What a tapped tile opens, as XCSoar's InfoBox decides
 * (`xcs_tiles_click`): [action] "analysis" with an analysis [page]
 * ("barograph", "thermal_band", ...), "wind", "alternates",
 * "waypoints", "waypoint", "airspace", "team_code", "status" with a
 * [page], the panels "altitude", "maccready", "radio", "atc",
 * "simulator"; or "none".
 */
@Serializable
data class TileAction(val action: String, val page: String = "") {
    companion object {
        fun parse(text: String): TileAction = json.decodeFromString(serializer(), text)
    }
}
