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

/** A point of a chart that is not over time, SI units. */
data class XY(val x: Double, val y: Double)

private fun List<List<Double>>.toXY() = map { XY(it[0], it[1]) }

/** XCSoar's vario histogram: how often each climb rate was flown. */
@Serializable
data class VarioHistogram(
    @SerialName("mac_cready") val macCready: Double = 0.0,
    /** The polar's sink at best glide, negative m/s. */
    @SerialName("cruise_sink") val cruiseSink: Double = 0.0,
    @SerialName("cruise") private val cruiseRaw: List<List<Double>> = emptyList(),
    @SerialName("circling") private val circlingRaw: List<List<Double>> = emptyList(),
) {
    /** [XY.x] climb rate (m/s), [XY.y] how often. */
    val cruise get() = cruiseRaw.toXY()
    val circling get() = circlingRaw.toXY()
}

/** XCSoar's thermal band: climb rate by height above the terrain base. */
@Serializable
data class ThermalBandInfo(
    /** Height above the terrain base now, m. */
    val height: Double? = null,
    @SerialName("mac_cready") val macCready: Double = 0.0,
    /** The chart's height range, m. */
    val range: List<Double>? = null,
    @SerialName("working_band") val workingBand: List<Double>? = null,
    /** [XY.x] climb rate (m/s), [XY.y] height: all climbs so far. */
    @SerialName("collection") private val collectionRaw: List<List<Double>> = emptyList(),
    /** The climb now, if any. */
    @SerialName("current") private val currentRaw: List<List<Double>> = emptyList(),
    /** The MacCready the risk factor allows at each height. */
    @SerialName("risk_mc") private val riskRaw: List<List<Double>> = emptyList(),
) {
    val collection get() = collectionRaw.toXY()
    val current get() = currentRaw.toXY()
    val riskMacCready get() = riskRaw.toXY()
}

/** The wind at one height: m, m/s, degrees it blows from. */
data class WindLevel(val height: Double, val speed: Double, val bearing: Double)

@Serializable
data class WindChart(@SerialName("levels") private val levelsRaw: List<List<Double>> = emptyList()) {
    /** From the lowest climb base to the highest ceiling. */
    val levels get() = levelsRaw.map { WindLevel(it[0], it[1], it[2]) }
}

/** XCSoar's polar page: the polar and the speeds it gives, m/s. */
@Serializable
data class PolarChart(
    @SerialName("mac_cready") val macCready: Double = 0.0,
    @SerialName("v_min") val vMin: Double = 0.0,
    @SerialName("v_max") val vMax: Double = 0.0,
    @SerialName("polar") private val polarRaw: List<List<Double>> = emptyList(),
    @SerialName("dolphin") private val dolphinRaw: List<List<Double>> = emptyList(),
    @SerialName("climbs") private val climbsRaw: List<List<Double>> = emptyList(),
    @SerialName("best_glide") private val bestGlideRaw: List<List<Double>> = emptyList(),
    @SerialName("best_ld") val bestLd: Double = 0.0,
    @SerialName("v_best_ld") val vBestLd: Double = 0.0,
    @SerialName("min_sink") val minSink: Double = 0.0,
    @SerialName("v_min_sink") val vMinSink: Double = 0.0,
    /** kg */
    val mass: Double = 0.0,
    /** kg/m² */
    @SerialName("wing_loading") val wingLoading: Double? = null,
) {
    /** [XY.x] airspeed, [XY.y] vertical speed (sink negative). */
    val polar get() = polarRaw.toXY()
    /** The dolphin speed command at the current MacCready. */
    val dolphin get() = dolphinRaw.toXY()
    /** Climb rates flown at each speed. */
    val climbs get() = climbsRaw.toXY()
    /** The tangent from MacCready to the polar. */
    val bestGlide get() = bestGlideRaw.toXY()
    val isValid get() = polarRaw.isNotEmpty()
}

/** Best glide and average speed at one MacCready, m/s. */
data class MacCreadySpeeds(val macCready: Double, val vOpt: Double, val vAverage: Double)

/** XCSoar's MacCready page. */
@Serializable
data class MacCreadyChart(
    @SerialName("mac_cready") val macCready: Double = 0.0,
    @SerialName("v_max") val vMax: Double = 0.0,
    @SerialName("speeds") private val speedsRaw: List<List<Double>> = emptyList(),
    @SerialName("v_opt") val vOpt: Double = 0.0,
    @SerialName("v_average") val vAverage: Double = 0.0,
    val mass: Double = 0.0,
    @SerialName("wing_loading") val wingLoading: Double? = null,
) {
    val speeds get() = speedsRaw.map { MacCreadySpeeds(it[0], it[1], it[2]) }
    val isValid get() = speedsRaw.isNotEmpty()
}

/** One 100 m level of the temperature trace, kelvin. */
data class TemperatureLevel(val height: Double, val dry: Double, val air: Double,
                            val dewPoint: Double?)

/** XCSoar's temperature trace (needs a temperature probe). */
@Serializable
data class TemperatureTrace(
    @SerialName("levels") private val levelsRaw: List<List<Double?>> = emptyList(),
    /** m */
    @SerialName("thermal_height") val thermalHeight: Double? = null,
    @SerialName("cloud_base") val cloudBase: Double? = null,
) {
    val levels get() = levelsRaw.map { TemperatureLevel(it[0]!!, it[1]!!, it[2]!!, it[3]) }
}

@Serializable
data class TaskChartPoint(
    val name: String,
    @SerialName("location") private val locationRaw: List<Double>,
    @SerialName("target") private val targetRaw: List<Double>,
) {
    val location get() = LatLon(locationRaw[0], locationRaw[1])
    /** Where the task goes from this point (AAT targets). */
    val target get() = LatLon(targetRaw[0], targetRaw[1])
}

/** XCSoar's task page; no points without a valid ordered task. */
@Serializable
data class TaskChart(
    val points: List<TaskChartPoint> = emptyList(),
    /** m */
    @SerialName("distance_to_go") val distanceToGo: Double? = null,
    /** s */
    @SerialName("task_to_go") val taskToGo: Double? = null,
    /** s, AAT only */
    @SerialName("aat_to_go") val aatToGo: Double? = null,
    /** m/s, AAT only */
    @SerialName("target_speed") val targetSpeed: Double? = null,
)

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
    @SerialName("vario_histogram") val varioHistogram: VarioHistogram = VarioHistogram(),
    @SerialName("thermal_band") val thermalBand: ThermalBandInfo = ThermalBandInfo(),
    val wind: WindChart = WindChart(),
    val polar: PolarChart = PolarChart(),
    @SerialName("mac_cready") val macCready: MacCreadyChart = MacCreadyChart(),
    val temperature: TemperatureTrace = TemperatureTrace(),
    val task: TaskChart = TaskChart(),
    /** null when not moving (no track). */
    val airspace: AirspaceCrossSection? = null,
) {
    companion object {
        fun parse(text: String): Analysis = json.decodeFromString(serializer(), text)
    }
}

/**
 * The airspace page (upstream's CrossSectionRenderer): [range] m along
 * the track, the [heights] shown, the terrain in slices (null: unknown),
 * the airspaces crossed and the glide line at MacCready.
 */
@Serializable
data class AirspaceCrossSection(
    val range: Double = 0.0,
    val heights: List<Double> = emptyList(),
    val altitude: Double? = null,
    val terrain: List<Double?> = emptyList(),
    val airspaces: List<CrossSectionAirspace> = emptyList(),
    /** [distance, height] from the aircraft to where the glide ends */
    val glide: List<List<Double>> = emptyList(),
)

/** One airspace on the cross-section; [spans] are [from, to] m along it. */
@Serializable
data class CrossSectionAirspace(
    val name: String = "",
    @SerialName("class") val airspaceClass: String = "",
    val top: Double = 0.0,
    /** 0 when it starts at the ground */
    val base: Double = 0.0,
    /** XCSoar's colours for its class, "#RRGGBB" */
    val fill: String = "",
    val border: String = "",
    val spans: List<List<Double>> = emptyList(),
)
