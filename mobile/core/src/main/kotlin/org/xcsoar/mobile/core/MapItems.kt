// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One thing on the map (`xcs_map_items_at`, core/api/xcsoar_core.h):
 * an airspace, waypoint, the terrain at the point...  Fields that do not
 * apply to [type] are null.
 */
@Serializable
data class MapItemInfo(
    /** location, self, task, airspace, thermal, waypoint, traffic, other */
    val type: String,
    val name: String? = null,
    val detail: String? = null,
    /** Airspace class or type, formatted by XCSoar. */
    val `class`: String? = null,
    /** Airspace limits, formatted by XCSoar ("FL95", "SFC", "1500 m AGL"). */
    val top: String? = null,
    val base: String? = null,
    /** Metres. */
    val elevation: Double? = null,
    /** MHz, formatted. */
    val frequency: String? = null,
    val landable: Boolean? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseList(text: String): List<MapItemInfo> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}
