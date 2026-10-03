// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
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

/** One of XCSoar's airspace classes (`xcs_airspace_classes`). */
@Serializable
data class AirspaceClassInfo(
    /** XCSoar's number for the class. */
    @SerialName("class") val code: Int,
    val name: String,
    /** Drawn on the map. */
    val display: Boolean,
    /** Warned of. */
    val warning: Boolean,
    /** Airspaces of this class in the loaded files. */
    val count: Int = 0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseList(text: String): List<AirspaceClassInfo> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}

/**
 * How airspace warnings reach the pilot (`xcs_airspace_option`):
 * whether XCSoar computes them at all, a tone and a vibration for a new
 * or worse one, and how long the banner stays (0: until acknowledged).
 */
data class AirspaceAlerts(
    val warnings: Boolean = true,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val autoHideSeconds: Int = 0,
    /** Warn this long before entering, s. */
    val warningSeconds: Int = 30,
    /** An acknowledged warning stays quiet this long, s. */
    val ackSeconds: Int = 30,
) {
    companion object {
        /** The banner's time before it hides itself, when the pilot wants that. */
        const val AUTO_HIDE_SECONDS = 10
    }
}

/** The airspace alert options; the defaults where the core has none. */
suspend fun XcsoarCore.airspaceAlerts(): AirspaceAlerts {
    val default = AirspaceAlerts()
    suspend fun flag(option: AirspaceOption, fallback: Boolean) =
        airspaceOption(option)?.let { it != 0 } ?: fallback
    return AirspaceAlerts(
        warnings = flag(AirspaceOption.WARNINGS, default.warnings),
        sound = flag(AirspaceOption.ALERT_SOUND, default.sound),
        vibration = flag(AirspaceOption.ALERT_VIBRATION, default.vibration),
        autoHideSeconds = airspaceOption(AirspaceOption.AUTO_HIDE) ?: default.autoHideSeconds,
        warningSeconds = airspaceOption(AirspaceOption.WARNING_TIME) ?: default.warningSeconds,
        ackSeconds = airspaceOption(AirspaceOption.ACK_TIME) ?: default.ackSeconds,
    )
}
