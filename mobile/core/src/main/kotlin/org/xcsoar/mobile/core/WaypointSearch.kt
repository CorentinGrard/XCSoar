// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Type filter of `xcs_waypoints_search`. */
enum class WaypointFilter(val code: Int) {
    ALL(0),
    /** Airfields and outlandings. */
    LANDABLE(1),
    AIRPORT(2),
}

/** One waypoint found by `xcs_waypoints_search`, nearest first. */
@Serializable
data class WaypointInfo(
    val id: Int,
    val name: String,
    val landable: Boolean = false,
    val airport: Boolean = false,
    /** Metres. */
    val elevation: Double? = null,
    /** Metres from the aircraft (else from home). */
    val distance: Double? = null,
    /** Degrees true. */
    val bearing: Double? = null,
    /** Landables with a GPS fix: can it be reached, and with what height (m). */
    val reachable: Boolean? = null,
    val arrival: Int? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseList(text: String): List<WaypointInfo> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}
