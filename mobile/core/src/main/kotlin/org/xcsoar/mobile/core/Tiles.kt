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
