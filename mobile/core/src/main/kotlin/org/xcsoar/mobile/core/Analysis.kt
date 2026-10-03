// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** One point of a chart: [t] hours of flight, [y] in SI units. */
data class ChartPoint(val t: Double, val y: Double)

/** A least-squares line across a chart: y = [y0] + [gradient] × t. */
@Serializable
data class Trend(val y0: Double, val gradient: Double) {
    fun at(t: Double) = y0 + gradient * t
}

/** A task point reached, [t] hours after take-off. */
@Serializable
data class TaskLeg(val index: Int, val t: Double)

private fun List<List<Double>>.toPoints() = map { ChartPoint(it[0], it[1]) }

/** XCSoar's barograph page; empty lists before there is data. */
@Serializable
data class Barograph(
    @SerialName("altitude") private val altitudeRaw: List<List<Double>> = emptyList(),
    @SerialName("terrain") private val terrainRaw: List<List<Double>> = emptyList(),
    /** Climb bases, as a line from the second climb on, a trend before. */
    @SerialName("base") private val baseRaw: List<List<Double>> = emptyList(),
    @SerialName("base_trend") val baseTrend: Trend? = null,
    /** Climb tops, likewise. */
    @SerialName("ceiling") private val ceilingRaw: List<List<Double>> = emptyList(),
    @SerialName("ceiling_trend") val ceilingTrend: Trend? = null,
    /** Lowest and highest working height, m. */
    @SerialName("working_band") val workingBand: List<Double>? = null,
    /** How fast the climb tops rise, m per hour (from the fourth climb). */
    @SerialName("ceiling_gradient") val ceilingGradient: Double? = null,
) {
    val altitude get() = altitudeRaw.toPoints()
    val terrain get() = terrainRaw.toPoints()
    val base get() = baseRaw.toPoints()
    val ceiling get() = ceilingRaw.toPoints()
}

/** One climb: started [t] hours into the flight, lasted [duration] hours. */
data class ClimbBar(val t: Double, val lift: Double, val duration: Double)

/** XCSoar's climb history page. */
@Serializable
data class ClimbHistory(
    @SerialName("mac_cready") val macCready: Double = 0.0,
    @SerialName("thermals") private val thermalsRaw: List<List<Double>> = emptyList(),
    val trend: Trend? = null,
    /** Average climb, m/s. */
    val average: Double? = null,
    /** Climb trend, m/s per hour. */
    val gradient: Double? = null,
) {
    val thermals get() = thermalsRaw.map { ClimbBar(it[0], it[1], it[2]) }
}

/** XCSoar's task speed page. */
@Serializable
data class TaskSpeedHistory(
    /** The polar's average cross-country speed at the current MC ("Vest"). */
    val estimated: Double,
    @SerialName("speeds") private val speedsRaw: List<List<Double>> = emptyList(),
    val trend: Trend? = null,
    /** Average task speed ("Vave"). */
    val average: Double,
) {
    val speeds get() = speedsRaw.toPoints()
}

data class LatLon(val latitude: Double, val longitude: Double)

private fun List<List<Double>>.toLatLon() = map { LatLon(it[0], it[1]) }

@Serializable
data class ContestResultInfo(
    /** "Classic", "FAI", "Plus", "Free", "Triangle", or "" if only one. */
    val label: String = "",
    /** m */
    val distance: Double = 0.0,
    val score: Double = 0.0,
    /** s */
    val time: Double = 0.0,
    /** m/s */
    val speed: Double = 0.0,
    @SerialName("points") private val pointsRaw: List<List<Double>> = emptyList(),
) {
    /** The optimised path. */
    val points get() = pointsRaw.toLatLon()
    val isDefined get() = score > 0
}

/** XCSoar's contest page. */
@Serializable
data class ContestInfo(
    /** The contest chosen in the settings, XCSoar's name. */
    val name: String = "",
    val results: List<ContestResultInfo> = emptyList(),
    @SerialName("trace") private val traceRaw: List<List<Double>> = emptyList(),
) {
    /** The flight, thinned. */
    val trace get() = traceRaw.toLatLon()
}

/** XCSoar's analysis pages (`xcs_get_analysis`). */
@Serializable
data class Analysis(
    /** Hours since take-off; null when not flying. */
    @SerialName("flight_time") val flightTime: Double? = null,
    val legs: List<TaskLeg> = emptyList(),
    val barograph: Barograph = Barograph(),
    val climb: ClimbHistory = ClimbHistory(),
    /** null without an ordered task or before two task speed samples. */
    @SerialName("task_speed") val taskSpeed: TaskSpeedHistory? = null,
    val contest: ContestInfo = ContestInfo(),
) {
    companion object {
        fun parse(text: String): Analysis = json.decodeFromString(serializer(), text)
    }
}
