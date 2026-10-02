// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** An active airspace warning (`xcs_get_airspace_warnings`). */
@Serializable
data class AirspaceWarningInfo(
    /** For [XcsoarCore.acknowledgeAirspace]. */
    val id: String,
    /** inside, near or task */
    val state: String,
    val name: String,
    val `class`: String = "",
    /** Formatted by XCSoar ("FL95", "SFC"). */
    val top: String = "",
    val base: String = "",
    /** Metres to the airspace, when known. */
    val distance: Double? = null,
    /** Seconds to the airspace, when known. */
    val time: Double? = null,
) {
    val inside: Boolean get() = state == "inside"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseList(text: String): List<AirspaceWarningInfo> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}
