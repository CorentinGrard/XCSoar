// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * A weather station and its last METAR and TAF from NOAA
 * (`xcs_weather_list`).  SI units; null where METAR did not say or
 * nothing was downloaded yet.
 */
@Serializable
data class WeatherStation(
    /** Four letter ICAO code. */
    val code: String,
    val name: String? = null,
    /** The METAR as received. */
    val metar: String? = null,
    /** ISO 8601, UTC. */
    @SerialName("metar_time") val metarTime: String? = null,
    val taf: String? = null,
    @SerialName("taf_time") val tafTime: String? = null,
    /** hPa */
    val qnh: Double? = null,
    /** degrees the wind comes from */
    @SerialName("wind_bearing") val windBearing: Double? = null,
    /** m/s */
    @SerialName("wind_speed") val windSpeed: Double? = null,
    /** K */
    val temperature: Double? = null,
    /** K */
    @SerialName("dew_point") val dewPoint: Double? = null,
    /** m */
    val visibility: Int? = null,
    val cavok: Boolean = false,
    /** XCSoar's decoded report, in the pilot's units and language. */
    val text: String? = null,
) {
    val downloaded get() = metar != null || taf != null

    companion object {
        /** NOAAStore::IsValidCode(): four ASCII letters or digits. */
        fun isValidCode(code: String) =
            code.length == 4 && code.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

        /** What the profile holds (CoreWeather.cpp). */
        const val MAX_STATIONS = 20

        fun parseList(text: String): List<WeatherStation> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}
