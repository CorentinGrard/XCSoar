// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

/**
 * The live state the core publishes, decoded from `xcs_flight_snapshot`
 * (core/api/xcsoar_core.h).
 *
 * All values are SI units: metres, metres per second, degrees, seconds.
 * A value is `null` when the core has no valid data for it; the UI must
 * then show dashes, never an old number (doc/architecture.rst, "Displayed
 * data").
 */
data class FlightState(
    /** Increases with every snapshot. */
    val sequence: Long,

    /** UTC, seconds since 1970-01-01. */
    val timeUtc: Double?,
    /** Seconds since take-off; 0 before. */
    val flightTime: Double,

    val position: GeoPosition?,
    /** Track over ground, degrees true. */
    val track: Double?,
    val groundSpeed: Double?,
    val trueAirspeed: Double?,
    val indicatedAirspeed: Double?,

    val gpsAltitude: Double?,
    val baroAltitude: Double?,
    /** The altitude XCSoar uses for its calculations. */
    val navAltitude: Double?,
    val terrainAltitude: Double?,
    val altitudeAgl: Double?,

    /** Total energy vario. */
    val vario: Double?,
    /** 30 s average of [vario]. */
    val averageVario: Double?,
    val nettoVario: Double?,

    val wind: Wind?,
    val macCready: Double,

    val next: NextWaypoint?,
    val finalGlide: FinalGlide?,

    val gpsReal: Boolean,
    val flying: Boolean,
    val circling: Boolean,
    /** Above final glide to the task finish. */
    val aboveFinalGlide: Boolean,
    val replay: Boolean,

    /** Speed to fly, IAS m/s. */
    val speedToFly: Double? = null,
    /** Current glide ratio over ground. */
    val ld: Double? = null,
    /** Glide ratio needed to the next point; 0 = no glide needed ("+++"). */
    val ldRequired: Double? = null,
    /** Seconds to the next point at the current MacCready. */
    val nextTimeRemaining: Double? = null,
    /** Achieved task speed, m/s. */
    val taskSpeed: Double? = null,
    /** The thermal being climbed. */
    val currentThermal: Thermal? = null,
    val lastThermal: Thermal? = null,

    /** Water ballast on board, litres. */
    val ballast: Double = 0.0,
    /** The plane's maximum water ballast, litres; 0 if it carries none. */
    val maxBallast: Double = 0.0,
    /** Performance left by bugs: 1 clean, 0.5 = sink rate doubled. */
    val bugs: Double = 1.0,
    /** kg/m², null if the plane's wing area is unknown. */
    val wingLoading: Double? = null,
)

/** One climb: average lift (m/s), height gained (m), time (s). */
data class Thermal(val lift: Double, val gain: Double, val duration: Double)

data class GeoPosition(val latitude: Double, val longitude: Double)

/** The wind the glider flies in; [bearing] is where it comes from. */
data class Wind(val speed: Double, val bearing: Double)

data class NextWaypoint(
    /** Empty if the core has no name for it. */
    val name: String,
    val distance: Double,
    val bearing: Double,
    /** Above (positive) or below the glide path to the point. */
    val altitudeDifference: Double,
)

data class FinalGlide(
    val remainingDistance: Double,
    /** Above (positive) or below final glide to the finish. */
    val altitudeDifference: Double,
)
