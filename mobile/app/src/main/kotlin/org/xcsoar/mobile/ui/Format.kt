// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Temporary metric formatting for the first screens.
 *
 * To be replaced by the unit tables and format rules exported by the
 * core (mobile/docs/DECISIONS.md D9), so that the user's unit settings
 * apply and the output matches XCSoar's own Formatter.
 */
object Format {
    /** Shown for invalid values (doc/architecture.rst: dashes, never stale). */
    const val INVALID = "---"

    data class Value(val text: String, val unit: String)

    fun altitude(m: Double?) = Value(m?.let { it.roundToInt().toString() } ?: INVALID, "m")

    /** Signed, one decimal: "+1.2", "-0.8", "0.0". */
    fun vario(ms: Double?) = Value(ms?.let { signed(it, 1) } ?: INVALID, "m/s")

    fun speed(ms: Double?) = Value(ms?.let { (it * 3.6).roundToInt().toString() } ?: INVALID, "km/h")

    fun distance(m: Double?) = Value(
        when {
            m == null -> INVALID
            abs(m) < 100_000 -> f("%.1f", m / 1000)
            else -> (m / 1000).roundToInt().toString()
        }, "km")

    fun bearing(deg: Double?) = Value(deg?.let { f("%03d", ((it.roundToInt() % 360) + 360) % 360) } ?: INVALID, "°")

    fun altitudeDifference(m: Double?) = Value(m?.let { signed(it, 0) } ?: INVALID, "m")

    fun macCready(ms: Double) = Value(f("%.1f", ms), "m/s")

    fun ballast(litres: Double) = Value(litres.roundToInt().toString(), "l")

    /** Bugs as XCSoar shows them: the performance lost, "0" (clean) to "50". */
    fun bugs(bugs: Double) = Value(bugsPercent(bugs).toString(), "%")

    fun bugsPercent(bugs: Double) = ((1 - bugs) * 100).roundToInt()

    fun wingLoading(kgm2: Double?) = Value(kgm2?.let { f("%.1f", it) } ?: INVALID, "kg/m²")

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

    private fun signed(value: Double, decimals: Int): String {
        val text = f("%.${decimals}f", abs(value))
        return when {
            text.all { it == '0' || it == '.' } -> text
            value > 0 -> "+$text"
            else -> "-$text"
        }
    }
}
