// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

/** One of XCSoar's plane files (`xcs_planes_list`). */
@Serializable
data class PlaneInfo(
    val path: String,
    val registration: String,
    @SerialName("competition_id") val competitionId: String = "",
    val type: String = "",
    @SerialName("polar_name") val polarName: String = "",
    /** WeGlide's aircraft type; 0 if not chosen. */
    @SerialName("weglide_type") val weGlideType: Int = 0,
    @SerialName("double_seater") val doubleSeater: Boolean = false,
    @SerialName("empty_mass") val emptyMass: Double = 0.0,
    @SerialName("reference_mass") val referenceMass: Double = 0.0,
    /** The polar's three points: [speed, sink], m/s. */
    private val points: List<List<Double>> = emptyList(),
    /** litres */
    @SerialName("max_ballast") val maxBallast: Double = 0.0,
    /** s to dump full ballast */
    @SerialName("dump_time") val dumpTime: Int = 120,
    /** m/s, the limit of speed to fly; 0 none */
    @SerialName("max_speed") val maxSpeed: Double = 0.0,
    /** m² */
    @SerialName("wing_area") val wingArea: Double = 0.0,
    /** % */
    val handicap: Int = 100,
) {
    val details get() = PlaneDetails(emptyMass, referenceMass, maxBallast, dumpTime, maxSpeed,
                                     wingArea, handicap)

    val polar get() = PolarPoint.listOf(points)
}

/** One point of a polar: the sink rate (m/s, negative) at a speed (m/s). */
data class PolarPoint(val speed: Double, val sink: Double) {
    companion object {
        /** From the core's [[speed, sink], ...]; empty unless three points. */
        fun listOf(points: List<List<Double>>): List<PolarPoint> =
            points.takeIf { p -> p.size == 3 && p.all { it.size == 2 } }
                ?.map { PolarPoint(it[0], it[1]) }.orEmpty()
    }
}

/** A WinPilot polar file (.plr) as the core reads it (`xcs_polar_parse`). */
@Serializable
data class ImportedPolar(
    @SerialName("reference_mass") val referenceMass: Double,
    @SerialName("max_ballast") val maxBallast: Double = 0.0,
    @SerialName("wing_area") val wingArea: Double = 0.0,
    @SerialName("max_speed") val maxSpeed: Double = 0.0,
    private val points: List<List<Double>> = emptyList(),
) {
    val polar get() = PolarPoint.listOf(points)

    companion object {
        fun parse(text: String): ImportedPolar = json.decodeFromString(serializer(), text)
    }
}

/** A plane's masses, ballast and limits (`xcs_plane_details`), SI units. */
data class PlaneDetails(
    val emptyMass: Double,
    val referenceMass: Double,
    val maxBallast: Double,
    val dumpTime: Int,
    val maxSpeed: Double,
    val wingArea: Double,
    val handicap: Int,
)

/** One built-in polar and the plane values it brings (`xcs_polar_get`). */
@Serializable
data class PolarInfo(
    val name: String,
    @SerialName("reference_mass") val referenceMass: Double,
    @SerialName("empty_mass") val emptyMass: Double = 0.0,
    @SerialName("max_ballast") val maxBallast: Double = 0.0,
    @SerialName("wing_area") val wingArea: Double = 0.0,
    @SerialName("max_speed") val maxSpeed: Double = 0.0,
    val handicap: Int = 0,
    private val points: List<List<Double>> = emptyList(),
) {
    val polar get() = PolarPoint.listOf(points)

    companion object {
        fun parse(text: String): PolarInfo = json.decodeFromString(serializer(), text)
    }
}

/** The planes, the active one and the one of the last take-off. */
@Serializable
data class PlaneList(
    /** Path of the active plane; empty if none. */
    val active: String = "",
    /** Path of the plane of the last take-off; empty if none. */
    @SerialName("last_flown") val lastFlown: String = "",
    val planes: List<PlaneInfo> = emptyList(),
) {
    /** What the picker selects first: the last plane flown, else the
        active one, else the only one. */
    val suggested: PlaneInfo?
        get() = planes.firstOrNull { it.path == lastFlown }
            ?: planes.firstOrNull { it.path == active }
            ?: planes.singleOrNull()

    companion object {
        fun parse(text: String): PlaneList = json.decodeFromString(serializer(), text)
        fun parsePolars(text: String): List<String> =
            json.decodeFromString(ListSerializer(String.serializer()), text)
    }
}

/** A plane to create ([path] empty) or change (`xcs_plane_save`). */
data class PlaneEdit(
    val path: String,
    val registration: String,
    val competitionId: String,
    val type: String,
    /** Index into the built-in polars; -1 keeps the plane's polar. */
    val polar: Int,
    val weGlideType: Int,
    val doubleSeater: Boolean,
    /** A polar of the pilot's (three points) and its name, replacing it. */
    val customPolar: List<PolarPoint>? = null,
    val customPolarName: String = "",
    /** kg, with [customPolar]; 0 keeps the plane's. */
    val customReferenceMass: Double = 0.0,
)

/** The names written to the IGC file (`xcs_crew_get`). */
@Serializable
data class Crew(
    val pilot: String = "",
    /** Empty when flying solo. */
    val copilot: String = "",
    /** Co-pilots flown with before, most recent first. */
    val copilots: List<String> = emptyList(),
) {
    companion object {
        fun parse(text: String): Crew = json.decodeFromString(serializer(), text)
    }
}

/** The pilot's WeGlide account (`xcs_weglide_get`). */
@Serializable
data class WeGlideSettings(
    val enabled: Boolean = false,
    @SerialName("pilot_id") val pilotId: Int = 0,
    /** "YYYY-MM-DD", or empty. */
    val birthdate: String = "",
) {
    val isConfigured get() = enabled && pilotId > 0 && birthdate.isNotEmpty()

    companion object {
        fun parse(text: String): WeGlideSettings = json.decodeFromString(serializer(), text)
    }
}

/** One of WeGlide's aircraft types. */
@Serializable
data class WeGlideAircraft(
    val id: Int,
    val name: String,
    /** Only known after `xcs_weglide_aircraft_get`. */
    @SerialName("double_seater") val doubleSeater: Boolean = false,
    val kind: String = "",
    @SerialName("sc_class") val scoringClass: String = "",
) {
    companion object {
        fun parse(text: String): WeGlideAircraft =
            json.decodeFromString(serializer(), WeGlideException.check(text))
        fun parseList(text: String): List<WeGlideAircraft> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}

/** The flight WeGlide made of an upload (`xcs_weglide_upload`). */
@Serializable
data class WeGlideFlight(
    @SerialName("flight_id") val flightId: Long,
    val url: String,
    val date: String = "",
    val pilot: String = "",
    val aircraft: String = "",
    val registration: String = "",
    @SerialName("competition_id") val competitionId: String = "",
) {
    /** The JSON [parse] reads, e.g. to remember the upload. */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        fun parse(text: String): WeGlideFlight =
            json.decodeFromString(serializer(), WeGlideException.check(text))
    }
}

/** WeGlide's task lists (`xcs_weglide_task_list`). */
enum class WeGlideTaskList(val code: Int, val label: String) {
    MINE(0, "Mine"), DECLARED(1, "Declared"), TODAY(2, "Today"), SCORES(3, "Scores"),
}

/** One of WeGlide's tasks (`xcs_weglide_tasks`), SI units. */
@Serializable
data class WeGlideTask(
    val id: Long,
    val name: String = "",
    @SerialName("user_name") val userName: String = "",
    /** m */
    val distance: Double = 0.0,
    /** "free", "free4", "triangle" or "unknown" */
    val kind: String = "",
    val ruleset: String = "",
    @SerialName("scoring_date") val scoringDate: String = "",
    val turnpoints: List<String> = emptyList(),
    val scores: List<WeGlideScore> = emptyList(),
) {
    companion object {
        fun parseList(text: String): List<WeGlideTask> =
            json.decodeFromString(ListSerializer(serializer()), WeGlideException.check(text))
    }
}

/** A pilot's score on a WeGlide task; speed in m/s. */
@Serializable
data class WeGlideScore(val user: String = "", val points: Double = 0.0,
                        val speed: Double = 0.0)

/** WeGlide or the network said no; [message] is theirs. */
class WeGlideException(message: String) : Exception(message) {
    companion object {
        /** @return [text] unless it is the core's {"error": ...} */
        fun check(text: String): String {
            val error = (json.parseToJsonElement(text) as? JsonObject)?.get("error")
            if (error != null)
                throw WeGlideException(error.jsonPrimitive.content)
            return text
        }
    }
}
