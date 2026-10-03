// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** NOTAMConfigPanel's settings (`xcs_notam_settings_get` / `_set`). */
@Serializable
data class NotamSettings(
    val enabled: Boolean = false,
    /** Around the aircraft, 1..[MAX_RADIUS_KM]. */
    @SerialName("radius_km") val radiusKm: Int = 50,
    /** 0: only when asked; at most [MAX_REFRESH_MIN]. */
    @SerialName("refresh_interval_min") val refreshMinutes: Int = 30,
    @SerialName("show_only_effective") val onlyEffective: Boolean = true,
    @SerialName("show_ifr") val showIfr: Boolean = false,
    /** NOTAMs larger than this are hidden; 0 shows all. */
    @SerialName("max_radius_m") val maxRadiusM: Int = 100_000,
    /** Q-code prefixes to hide, separated by spaces. */
    @SerialName("hidden_qcodes") val hiddenQCodes: String = "QA QK QN QOL QOA QOBTT",
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MAX_RADIUS_KM = 185
        const val MAX_REFRESH_MIN = 240

        fun parse(text: String): NotamSettings = json.decodeFromString(serializer(), text)
    }
}

/** The NOTAMs the settings show, nearest first (`xcs_notam_list`). */
@Serializable
data class NotamList(
    val loading: Boolean = false,
    /** ISO 8601 UTC of the last download; null if none. */
    val updated: String? = null,
    /** All loaded, before the filters. */
    val total: Int = 0,
    val notams: List<NotamInfo> = emptyList(),
) {
    companion object {
        fun parse(text: String): NotamList = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class NotamInfo(
    /** e.g. "A1234/26" */
    val number: String = "",
    /** ICAO location, e.g. "LFMM" */
    val location: String = "",
    val text: String = "",
    /** ISO 8601 UTC */
    val start: String = "",
    /** ISO 8601 UTC; null when [permanent]. */
    val end: String? = null,
    val permanent: Boolean = false,
    val active: Boolean = false,
    /** XCSoar's airspace limits ("SFC", "FL095"); empty if not given. */
    val lower: String = "",
    val upper: String = "",
    /** m from the aircraft; null without a fix. */
    val distance: Double? = null,
)
