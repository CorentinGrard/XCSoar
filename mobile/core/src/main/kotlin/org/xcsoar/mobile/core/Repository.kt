// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** XCSoar's file catalogue, the index the core parses. */
const val REPOSITORY_URI = "https://download.xcsoar.org/repository"

/** One downloadable file (`xcs_repository_list`, core/api/xcsoar_core.h). */
@Serializable
data class RepositoryFile(
    val name: String,
    val uri: String,
    /** map, airspace, waypoint or other */
    val type: String,
    /** Country code, e.g. "fr"; may be empty. */
    val area: String = "",
    val description: String = "",
    /** YYYY-MM-DD */
    val updated: String? = null,
    /** XCSoarData sub-folder for this kind of file. */
    val folder: String? = null,
    /** Hex SHA-256 of the file, to check the download. */
    val sha256: String? = null,
) {
    /** The data file kind this file can be used as, if any. */
    val dataFile: DataFile?
        get() = when (type) {
            "map" -> DataFile.MAP
            "airspace" -> DataFile.AIRSPACE
            "waypoint" -> DataFile.WAYPOINTS
            else -> null
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseList(text: String): List<RepositoryFile> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}
