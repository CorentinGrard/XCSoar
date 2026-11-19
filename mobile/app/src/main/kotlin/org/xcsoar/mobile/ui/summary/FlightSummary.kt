// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.summary

import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.ClimbBar
import org.xcsoar.mobile.core.ContestInfo
import org.xcsoar.mobile.core.ContestResultInfo
import org.xcsoar.mobile.core.FlightTimes

/** A flight ended: what the summary reads it from. */
sealed interface FlightEnd {
    /** Landed: the glide computer still has the flight until the next take-off. */
    data object Landed : FlightEnd

    /** A replay reached its end: its flight's analysis, which the core forgot. */
    data class Replayed(val analysis: String) : FlightEnd
}

/**
 * The end of a flight at a glance (XCSoar's analysis): times, the
 * contest result, the climbs, the highest point and the flight's path.
 */
data class FlightSummary(
    val times: FlightTimes?,
    val contest: ContestInfo,
    /** The contest result with the most points; null before one. */
    val result: ContestResultInfo?,
    /** The climb with the best average. */
    val bestClimb: ClimbBar?,
    /** m/s, all climbs; null before one. */
    val averageClimb: Double?,
    val climbs: Int,
    /** m */
    val highest: Double?,
    /** A replayed flight: nothing was recorded. */
    val replay: Boolean,
) {
    /** s; 0 without a take-off. */
    val duration: Double get() = times?.duration ?: 0.0

    companion object {
        /** Shorter flights (a hop, a launch failure) get no summary. */
        const val MIN_SECONDS = 120.0

        fun of(a: Analysis, replay: Boolean) = FlightSummary(
            times = a.flight,
            contest = a.contest,
            result = a.contest.results.filter { it.isDefined }.maxByOrNull { it.score },
            bestClimb = a.climb.thermals.maxByOrNull { it.lift },
            averageClimb = a.climb.average,
            climbs = a.climb.thermals.size,
            highest = a.barograph.altitude.maxOfOrNull { it.y },
            replay = replay,
        )
    }
}

/** Height gained in a climb (its lift is m/s, its duration hours). */
val ClimbBar.gain: Double get() = lift * duration * 3600
