// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.xcsoar.mobile.core.UnitFormatter
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.UnitInfo
import org.xcsoar.mobile.core.UnitSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Values as the pilot reads them: XCSoar's units and Formatter rules
 * ([UnitFormatter], D9), in the units chosen in the core.
 *
 * [units] is Compose state: what formats a value while composing is
 * composed again when the pilot changes units.  Like XCSoar's
 * Units::current, there is one set for the process.
 */
object Format {
    /** Shown for invalid values (doc/architecture.rst: dashes, never stale). */
    const val INVALID = "---"

    data class Value(val text: String, val unit: String)

    var units: UnitSettings by mutableStateOf(UnitSettings.METRIC)

    private val formatter get() = UnitFormatter(units)

    fun unit(group: UnitGroup): UnitInfo = units.unitOf(group)

    /** Altitudes and heights: whole units ("1842 m", "6043 ft"). */
    fun altitude(m: Double?): Value {
        val unit = unit(UnitGroup.ALTITUDE)
        return Value(m?.let { formatter.altitude(it, unit) } ?: INVALID, unit.name)
    }

    /** Above (+) or below (-) something: "+120", "-45". */
    fun altitudeDifference(m: Double?): Value {
        val unit = unit(UnitGroup.ALTITUDE)
        return Value(m?.let { formatter.relativeAltitude(it, unit) } ?: INVALID, unit.name)
    }

    /**
     * Climb or sink with its sign ("+1.2", "-0.8"); zero has none
     * ("0.0"), where XCSoar would print "+0.0" or "-0.0".
     */
    fun vario(ms: Double?): Value {
        val unit = unit(UnitGroup.VERTICAL_SPEED)
        val text = ms?.let { formatter.verticalSpeed(it, unit, sign = true) }
            ?.let { if (it.drop(1).all { c -> c == '0' || c == '.' }) it.drop(1) else it }
        return Value(text ?: INVALID, unit.name)
    }

    /** MacCready: vertical speed without a sign. */
    fun macCready(ms: Double): Value {
        val unit = unit(UnitGroup.VERTICAL_SPEED)
        return Value(formatter.verticalSpeed(ms, unit, sign = false), unit.name)
    }

    /**
     * One MacCready step from [ms] in [direction] (±1; 0 only snaps to
     * the grid), in m/s: XCSoar's GetVerticalSpeedStep() (0.1 m/s,
     * 0.2 kt, 10 fpm), on that unit's grid.
     */
    fun stepVerticalSpeed(ms: Double, direction: Int): Double {
        val unit = unit(UnitGroup.VERTICAL_SPEED)
        val step = when (unit.unit) {
            UnitInfo.FEET_PER_MINUTE -> 10.0
            UnitInfo.KNOTS -> 0.2
            else -> 0.1
        }
        val user = round(unit.toUser(ms) / step + direction) * step
        return unit.toSi(user)
    }

    /**
     * One safety height step from [m] in [direction] (±1), in metres:
     * 10 m or 50 ft, on that unit's grid.
     */
    fun stepAltitude(m: Double, direction: Int): Double {
        val unit = unit(UnitGroup.ALTITUDE)
        val step = if (unit.unit == UnitInfo.FEET) 50.0 else 10.0
        val user = round(unit.toUser(m) / step + direction) * step
        return unit.toSi(user)
    }

    /** Aircraft speeds, whole units. */
    fun speed(ms: Double?) = speed(ms, UnitGroup.HORIZONTAL_SPEED)

    fun windSpeed(ms: Double?) = speed(ms, UnitGroup.WIND_SPEED)

    fun taskSpeed(ms: Double?) = speed(ms, UnitGroup.TASK_SPEED)

    private fun speed(ms: Double?, group: UnitGroup): Value {
        val unit = unit(group)
        return Value(ms?.let { formatter.speed(it, unit, precision = false) } ?: INVALID,
                     unit.name)
    }

    /** XCSoar's FormatUserDistanceSmart(): "7.30 km", "23.4 km", "123 km". */
    fun distance(m: Double?): Value {
        val unit = unit(UnitGroup.DISTANCE)
        if (m == null)
            return Value(INVALID, unit.name)
        val (text, used) = formatter.distanceSmart(m, unit)
        return Value(text.removeSuffix(" ${used.name}"), used.name)
    }

    fun bearing(deg: Double?) =
        Value(deg?.let { f("%03d", ((UnitFormatter.iround(it) % 360) + 360) % 360) } ?: INVALID,
              "°")

    /** Water ballast, litres (XCSoar has no other unit for it). */
    fun ballast(litres: Double) = Value(UnitFormatter.iround(litres).toString(), "l")

    /** Bugs as XCSoar shows them: the performance lost, "0" (clean) to "50". */
    fun bugs(bugs: Double) = Value(bugsPercent(bugs).toString(), "%")

    fun bugsPercent(bugs: Double) = UnitFormatter.iround((1 - bugs) * 100)

    fun wingLoading(kgm2: Double?): Value {
        val unit = unit(UnitGroup.WING_LOADING)
        return Value(kgm2?.let { formatter.wingLoading(it, unit) } ?: INVALID, unit.name)
    }

    /** Pressure like XCSoar's FormatPressure(): "1013" hPa, "29.92" inHg. */
    fun pressure(hpa: Double?): Value {
        val unit = unit(UnitGroup.PRESSURE)
        return Value(hpa?.let { formatter.pressure(it, unit) } ?: INVALID, unit.name)
    }

    /**
     * One QNH step from [hpa] in [direction] (±1; 0 only snaps to the
     * grid), in hPa: XCSoar's GetPressureStep() (0.01 inHg, else 1).
     */
    fun stepPressure(hpa: Double, direction: Int): Double {
        val unit = unit(UnitGroup.PRESSURE)
        val step = if (unit.unit == UnitInfo.INCH_MERCURY) 0.01 else 1.0
        val user = round(unit.toUser(hpa) / step + direction) * step
        return unit.toSi(user)
    }

    /** Hours and minutes, "2:05"; for flight time. */
    fun duration(s: Double?) = Value(
        s?.takeIf { it >= 0 }?.let {
            val minutes = (it / 60).toLong()
            f("%d:%02d", minutes / 60, minutes % 60)
        } ?: INVALID, "")

    /** Minutes and seconds, "3:24"; for thermals and times to go. */
    fun minutesSeconds(s: Double?) = Value(
        s?.takeIf { it >= 0 }?.let {
            val total = it.toLong()
            f("%d:%02d", total / 60, total % 60)
        } ?: INVALID, "")

    /** Glide ratio like XCSoar's FormatGlideRatio(): "27.4", "120". */
    fun glideRatio(ratio: Double?) = Value(
        when {
            ratio == null -> INVALID
            abs(ratio) < 100 -> f("%.1f", ratio)
            else -> f("%.0f", ratio)
        }, "")

    /** Required glide ratio; 0 or less means no glide needed: "+++". */
    fun requiredGlideRatio(ratio: Double?) =
        if (ratio != null && ratio <= 0) Value("+++", "") else glideRatio(ratio)

    /* "." as decimal separator for now, like XCSoar's Formatter */
    private fun f(format: String, vararg args: Any) = String.format(Locale.ROOT, format, *args)
}
