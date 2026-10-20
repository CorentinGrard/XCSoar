// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The pages some tiles open (core/host/CoreTilePages.hpp).  Texts are
 * formatted by the core in the pilot's units; "" where XCSoar shows
 * nothing.
 */

private val json = Json { ignoreUnknownKeys = true }

/** A waypoint a setting refers to. */
@Serializable
data class WaypointRef(val id: Int, val name: String)

/** XCSoar's team code dialog (`xcs_team_code_get`). */
@Serializable
data class TeamCodeInfo(
    /** This glider's code; needs a reference waypoint. */
    val own: String = "",
    /** The code the team mate reported. */
    val mate: String = "",
    val reference: WaypointRef? = null,
    /** To the mate: "12.3 km", "123°", "12°>". */
    val range: String = "",
    val bearing: String = "",
    @SerialName("relative_bearing") val relativeBearing: String = "",
    /** The FLARM competition number followed, if any. */
    @SerialName("flarm_lock") val flarmLock: String = "",
) {
    companion object {
        fun parse(text: String): TeamCodeInfo = json.decodeFromString(serializer(), text)
    }
}

/** Values of `xcs_radio_slot`. */
enum class RadioSlot(val code: Int) { ACTIVE(0), STANDBY(1) }

/** A radio frequency: "122.500" (MHz) or "" when none, and its name. */
@Serializable
data class RadioChannel(val frequency: String = "", val name: String = "")

/** The frequencies XCSoar sends to radios (`xcs_radio_get`). */
@Serializable
data class RadioInfo(
    val active: RadioChannel = RadioChannel(),
    val standby: RadioChannel = RadioChannel(),
) {
    operator fun get(slot: RadioSlot) = when (slot) {
        RadioSlot.ACTIVE -> active
        RadioSlot.STANDBY -> standby
    }

    companion object {
        fun parse(text: String): RadioInfo = json.decodeFromString(serializer(), text)
    }
}

/** The ATC radial's reference (`xcs_atc_get`). */
@Serializable
data class AtcInfo(
    /** The waypoint at the reference ("" if none there). */
    val waypoint: String = "",
    /** The reference, formatted; null without one. */
    val location: String? = null,
    /** Degrees, east positive. */
    val declination: Double = 0.0,
) {
    companion object {
        fun parse(text: String): AtcInfo = json.decodeFromString(serializer(), text)
    }
}

/** The airspace a tile asked for (`xcs_airspace_details`). */
@Serializable
data class AirspaceDetails(
    val name: String = "",
    @SerialName("class") val airspaceClass: String = "",
    val type: String = "",
    val top: String = "",
    val base: String = "",
    /** "120.505" (MHz) or "". */
    val frequency: String = "",
    val station: String = "",
    val squawk: String = "",
    /** "" without a GPS fix. */
    val distance: String = "",
    /** No warnings for it until tomorrow. */
    val acknowledged: Boolean = false,
) {
    companion object {
        /** null for "{}": no airspace. */
        fun parse(text: String): AirspaceDetails? =
            json.decodeFromString(serializer(), text).takeIf { it.name.isNotEmpty() }
    }
}
