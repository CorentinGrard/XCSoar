// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The kinds of data file, values of `xcs_data_file` (core/api/xcsoar_core.h). */
enum class DataFile(val code: Int) {
    /** Map (.xcm): terrain and topography, maybe waypoints and airspace. */
    MAP(1),
    /** Airspace (OpenAir .txt/.air, .sua). */
    AIRSPACE(2),
    /** Waypoints (.cup, .dat, ...). */
    WAYPOINTS(3),
}

/** What `xcs_get_data_status` reports: the configured files and what loaded. */
@Serializable
data class DataStatus(
    val map: MapStatus,
    val airspace: FileStatus,
    val waypoints: FileStatus,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): DataStatus = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class MapStatus(
    /** Absolute paths. */
    val files: List<String>,
    /** Whether the terrain of the map file was loaded. */
    val terrain: Boolean,
)

@Serializable
data class FileStatus(
    /** Absolute paths. */
    val files: List<String>,
    /** Items loaded, including those from the map file. */
    val count: Int,
)
