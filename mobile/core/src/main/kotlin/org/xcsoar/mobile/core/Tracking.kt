// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // the cloud's "not asked yet" is left out, never sent as null
    explicitNulls = false
}

/** Live tracking settings (`xcs_tracking_get` / `xcs_tracking_set`). */
@Serializable
data class TrackingSettings(
    val skylines: SkyLines = SkyLines(),
    val livetrack24: LiveTrack24 = LiveTrack24(),
    val cloud: Cloud = Cloud(),
) {
    @Serializable
    data class SkyLines(
        val enabled: Boolean = false,
        /** Also on a roaming mobile data connection. */
        val roaming: Boolean = true,
        /** s, one of [INTERVALS] */
        val interval: Int = 5,
        /** The positions of the pilot's SkyLines friends. */
        val traffic: Boolean = false,
        /** The positions of SkyLines users nearby. */
        @SerialName("near_traffic") val nearTraffic: Boolean = false,
        /** Hexadecimal; "0" is none. */
        val key: String = "0",
    )

    @Serializable
    data class LiveTrack24(
        val enabled: Boolean = false,
        val server: String = SERVERS.first(),
        val username: String = "",
        val password: String = "",
        /** s, one of [INTERVALS] */
        val interval: Int = 60,
        /** 0 glider (XCSoar's VehicleType). */
        @SerialName("vehicle_type") val vehicleType: Int = 0,
        @SerialName("vehicle_name") val vehicleName: String = "",
    )

    @Serializable
    data class Cloud(
        /** null until the pilot answered. */
        val enabled: Boolean? = null,
        /** Nearby traffic from the server, including OGN. */
        @SerialName("show_traffic") val showTraffic: Boolean = true,
        @SerialName("show_thermals") val showThermals: Boolean = true,
        val roaming: Boolean = true,
    )

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        /** XCSoar's tracking intervals, s (TrackingConfigPanel). */
        val INTERVALS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 120, 180, 300, 600, 900,
                               1200, 1800, 2400, 3000, 3600)

        /** XCSoar's LiveTrack24 servers. */
        val SERVERS = listOf("www.livetrack24.com", "test.livetrack24.com", "livexc.dhv.de")

        /** Strings the core takes: at most 63 bytes of UTF-8. */
        const val MAX_TEXT_BYTES = 63

        fun parse(text: String): TrackingSettings = json.decodeFromString(serializer(), text)

        /** A SkyLines key: up to 16 hexadecimal digits. */
        fun isValidKey(key: String) =
            key.length <= 16 && key.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }

        fun fits(text: String) = text.encodeToByteArray().size <= MAX_TEXT_BYTES
    }
}
