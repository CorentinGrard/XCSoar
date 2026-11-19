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
    /** map, airspace, waypoint, rasp or other */
    val type: String,
    /** Country code, e.g. "fr"; may be empty. */
    val area: String = "",
    val description: String = "",
    /** YYYY-MM-DD */
    val updated: String? = null,
    /** The region the file covers: west, south, east, north (degrees). */
    val bbox: List<Double>? = null,
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
            "rasp" -> DataFile.RASP
            else -> null
        }

    /** Whether [bbox] covers [position]; false when the repository does not say. */
    fun covers(position: GeoPosition): Boolean {
        val (west, south, east, north) = bbox?.takeIf { it.size == 4 } ?: return false
        if (position.latitude !in south..north) return false
        // a box across the antimeridian has its west edge east of its east edge
        return if (west <= east) position.longitude in west..east
               else position.longitude >= west || position.longitude <= east
    }

    /** The size of [bbox] in square degrees, to find the closest fit. */
    internal val bboxSize: Double
        get() {
            val (west, south, east, north) =
                bbox?.takeIf { it.size == 4 } ?: return Double.POSITIVE_INFINITY
            return (if (west <= east) east - west else east - west + 360) * (north - south)
        }

    /** Lower case [area]: the repository mixes "fr" and "AD". */
    val country: String get() = area.lowercase()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseList(text: String): List<RepositoryFile> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}

/**
 * What a first start offers for the region the pilot flies in: a map,
 * airspace and waypoints from XCSoar's repository (any may be missing).
 */
data class RegionFiles(
    /** Lower case country code, as the repository's areas. */
    val country: String,
    val map: RepositoryFile? = null,
    val airspace: RepositoryFile? = null,
    val waypoints: RepositoryFile? = null,
) {
    val files: List<RepositoryFile> get() = listOfNotNull(map, airspace, waypoints)

    companion object {
        /**
         * The country to offer files for: the first of [hints] (the
         * phone's network, SIM and language countries) whose airspace
         * or waypoints cover [position]; else the country whose files
         * cover it most closely; without a position, the first hint the
         * repository has files for.
         */
        fun country(files: List<RepositoryFile>, position: GeoPosition?,
                    hints: List<String>): String? {
            val countries = files.filter { it.isNational }.map { it.country }.toSet()
            val known = hints.map { it.lowercase() }.filter { it in countries }
            if (position == null) return known.firstOrNull()

            val covering = files.filter { it.isNational && it.covers(position) }
            return known.firstOrNull { c -> covering.any { it.country == c } }
                ?: covering.minByOrNull { it.bboxSize }?.country
                ?: known.firstOrNull()
        }

        /**
         * The files to offer for [country]: the map that covers
         * [position] most closely if the pilot is in that country, else
         * the country's largest; its airspace from OpenAIP and the
         * XCSoar waypoint file of its airfields (else OpenAIP's
         * airports), as XCSoar's repository names them.
         */
        fun suggest(files: List<RepositoryFile>, country: String,
                    position: GeoPosition?): RegionFiles {
            val c = country.lowercase()
            val own = files.filter { it.country == c }
            val here = position != null && own.any { it.isNational && it.covers(position) }
            // the plain maps: the "HighRes" ones are much larger downloads
            val maps = files.filter { it.type == "map" && !it.name.contains("HighRes") }
            val map = (if (here) maps.filter { it.covers(position!!) }.minByOrNull { it.bboxSize }
                       else null)
                ?: maps.filter { it.country == c }.maxByOrNull { it.bboxSize }

            fun pick(type: String, vararg preferred: String) = own
                .filter { it.type == type }
                .let { list ->
                    preferred.firstNotNullOfOrNull { suffix ->
                        list.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
                    } ?: list.firstOrNull()
                }

            return RegionFiles(
                c, map,
                airspace = pick("airspace", "-ASP-National-OpenAIP.txt"),
                waypoints = pick("waypoint", "-WPT-National-XCSoar.cup",
                                 "-WPT-Airports-OpenAIP.cupx"),
            )
        }

        /** The countries the repository has airspace or waypoints for. */
        fun countries(files: List<RepositoryFile>): List<String> =
            files.filter { it.isNational }.map { it.country }.distinct().sorted()

        private val RepositoryFile.isNational: Boolean
            get() = area.isNotEmpty() && (type == "airspace" || type == "waypoint")
    }
}
