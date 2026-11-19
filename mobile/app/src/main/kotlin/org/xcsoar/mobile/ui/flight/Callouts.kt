// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.NextWaypoint
import org.xcsoar.mobile.core.Thermal
import org.xcsoar.mobile.ui.Format
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Which callouts the pilot hears (Menu → Voice callouts); the app's
 * own choice, kept in its preferences.
 */
data class CalloutSettings(
    val airspace: Boolean = true,
    val finalGlide: Boolean = true,
    val taskPoints: Boolean = true,
    val climbs: Boolean = true,
) {
    val any: Boolean get() = airspace || finalGlide || taskPoints || climbs
}

/**
 * What the voice says, short and in the pilot's units: eyes stay
 * outside.  Numbers without their sign where words say it ("above").
 */
object Callouts {
    /** The shortest climb worth a summary: one turn for traffic is none. */
    const val MIN_CLIMB_SECONDS = 30.0

    /** "Airspace ahead: Grenoble CTR, in 40 seconds"; "Inside …". */
    fun airspace(warning: AirspaceWarningInfo): String? = when {
        warning.inside -> "Inside airspace: ${warning.name}"
        warning.state == "near" -> {
            val time = warning.time?.takeIf { it > 0 }?.let { s ->
                if (s < 90) "in ${(s / 10).roundToInt().coerceAtLeast(1) * 10} seconds"
                else "in ${(s / 60).roundToInt()} minutes"
            }
            val distance = warning.distance?.takeIf { time == null && it > 0 }?.let(::distance)
            listOfNotNull("Airspace ahead: ${warning.name}", time ?: distance)
                .joinToString(", ")
        }
        else -> null  // crossed by the task: a planning warning
    }

    /** Upstream's "above / below final glide" (ConditionMonitorFinalGlide). */
    fun finalGlide(above: Boolean, difference: Double?): String {
        val by = difference?.let { " by ${altitude(abs(it))}" } ?: ""
        return (if (above) "Above final glide" else "Below final glide") + by
    }

    /** "Turn point reached. Next: Grenoble, 23.4 kilometres". */
    fun taskPoint(event: GlideComputerEvent, next: NextWaypoint?): String? {
        val what = when (event) {
            GlideComputerEvent.TASK_START -> "Task started"
            GlideComputerEvent.TASK_NEXTWAYPOINT -> "Turn point reached"
            GlideComputerEvent.TASK_FINISH -> return "Task finished"
            else -> return null
        }
        val nextPart = next?.takeIf { it.name.isNotEmpty() }
            ?.let { n -> ". Next: ${n.name}" + (n.distance?.let { ", ${distance(it)}" } ?: "") }
            ?: ""
        return what + nextPart
    }

    /** Leaving a climb: "Climb 2.1, gained 450" (average lift, height). */
    fun climb(thermal: Thermal): String? {
        if (thermal.duration < MIN_CLIMB_SECONDS) return null
        val lift = Format.vario(thermal.lift).text.removePrefix("+")
        val gain = Format.altitude(thermal.gain).text
        return "Climb $lift, gained $gain"
    }

    /** "23.4 kilometres", "7.3 kilometres": no trailing zeros to read. */
    private fun distance(m: Double): String {
        val v = Format.distance(m)
        return "${number(v.text)} ${unitWord(v.unit)}"
    }

    private fun altitude(m: Double): String {
        val v = Format.altitude(m)
        return "${v.text} ${unitWord(v.unit)}"
    }

    private fun number(text: String): String =
        if ('.' in text) text.trimEnd('0').trimEnd('.') else text

    /** The unit as a word, so every voice reads it the same. */
    internal fun unitWord(unit: String): String = when (unit) {
        "km" -> "kilometres"
        "m" -> "metres"
        "ft" -> "feet"
        "NM" -> "nautical miles"
        "mi" -> "miles"
        else -> unit
    }
}
