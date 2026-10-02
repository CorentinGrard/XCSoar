// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/** One of XCSoar's units (src/Units/Unit.hpp, Descriptor.cpp). */
@Serializable
data class UnitInfo(
    /** XCSoar's Unit enum value. */
    val unit: Int,
    val name: String,
    /** user = SI × factor + offset */
    val factor: Double,
    val offset: Double = 0.0,
) {
    fun toUser(si: Double) = si * factor + offset
    fun toSi(user: Double) = (user - offset) / factor

    companion object {
        // XCSoar's Unit enum values the formatting rules depend on
        const val KILOMETER = 1
        const val NAUTICAL_MILES = 2
        const val STATUTE_MILES = 3
        const val KILOMETER_PER_HOUR = 4
        const val KNOTS = 5
        const val METER_PER_SECOND = 7
        const val FEET_PER_MINUTE = 8
        const val METER = 9
        const val FEET = 10
        const val DEGREES_CELCIUS = 13
        const val HECTOPASCAL = 15
        const val INCH_MERCURY = 18
        const val KG_PER_M2 = 19
        const val KG = 21
    }
}

/** Groups of `xcs_units_get` (XCSoar's UnitGroup). */
enum class UnitGroup(val code: Int) {
    DISTANCE(1),
    ALTITUDE(2),
    TEMPERATURE(3),
    HORIZONTAL_SPEED(4),
    VERTICAL_SPEED(5),
    WIND_SPEED(6),
    TASK_SPEED(7),
    PRESSURE(8),
    WING_LOADING(9),
    MASS(10),
}

/** The unit of one group and the ones the pilot may choose. */
@Serializable
data class UnitGroupInfo(
    val group: Int,
    val unit: Int,
    val choices: List<Int> = emptyList(),
)

@Serializable
data class UnitPreset(val name: String)

/**
 * XCSoar's units and the pilot's choice (`xcs_units_get`): every unit's
 * conversion, the unit of each group, and XCSoar's presets.
 */
@Serializable
data class UnitSettings(
    val units: List<UnitInfo>,
    val groups: List<UnitGroupInfo>,
    val presets: List<UnitPreset> = emptyList(),
    /** The preset the settings equal, or -1. */
    val preset: Int = -1,
) {
    private val byCode = units.associateBy { it.unit }

    fun unit(code: Int): UnitInfo = byCode.getValue(code)

    /** The configured unit of [group]. */
    fun unitOf(group: UnitGroup): UnitInfo =
        unit(groups.first { it.group == group.code }.unit)

    fun choices(group: UnitGroup): List<UnitInfo> =
        groups.firstOrNull { it.group == group.code }?.choices.orEmpty().map(::unit)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): UnitSettings = json.decodeFromString(serializer(), text)

        private fun u(code: Int, name: String, factor: Double, offset: Double = 0.0) =
            UnitInfo(code, name, factor, offset)

        /** Before the core answered: XCSoar's "European" preset. */
        val METRIC = UnitSettings(
            units = listOf(
                u(UnitInfo.KILOMETER, "km", 0.001),
                u(UnitInfo.METER, "m", 1.0),
                u(UnitInfo.DEGREES_CELCIUS, "°C", 1.0, -273.15),
                u(UnitInfo.KILOMETER_PER_HOUR, "km/h", 3.6),
                u(UnitInfo.METER_PER_SECOND, "m/s", 1.0),
                u(UnitInfo.HECTOPASCAL, "hPa", 1.0),
                u(UnitInfo.KG_PER_M2, "kg/m²", 1.0),
                u(UnitInfo.KG, "kg", 1.0),
            ),
            groups = listOf(
                UnitGroupInfo(UnitGroup.DISTANCE.code, UnitInfo.KILOMETER),
                UnitGroupInfo(UnitGroup.ALTITUDE.code, UnitInfo.METER),
                UnitGroupInfo(UnitGroup.TEMPERATURE.code, UnitInfo.DEGREES_CELCIUS),
                UnitGroupInfo(UnitGroup.HORIZONTAL_SPEED.code, UnitInfo.KILOMETER_PER_HOUR),
                UnitGroupInfo(UnitGroup.VERTICAL_SPEED.code, UnitInfo.METER_PER_SECOND),
                UnitGroupInfo(UnitGroup.WIND_SPEED.code, UnitInfo.KILOMETER_PER_HOUR),
                UnitGroupInfo(UnitGroup.TASK_SPEED.code, UnitInfo.KILOMETER_PER_HOUR),
                UnitGroupInfo(UnitGroup.PRESSURE.code, UnitInfo.HECTOPASCAL),
                UnitGroupInfo(UnitGroup.WING_LOADING.code, UnitInfo.KG_PER_M2),
                UnitGroupInfo(UnitGroup.MASS.code, UnitInfo.KG),
            ),
        )
    }
}

/**
 * XCSoar's Formatter/Units.cpp in Kotlin: the same rules and the same
 * rounding as its printf calls, so the app shows what XCSoar shows
 * (checked against format-golden.txt, which core/test/FormatGolden.cpp
 * writes).  Values are SI; outputs have no unit unless said.
 */
class UnitFormatter(private val settings: UnitSettings) {
    /** FormatAltitude(): whole units. */
    fun altitude(m: Double, unit: UnitInfo) = iround(unit.toUser(m)).toString()

    /** FormatRelativeAltitude(): whole units with a sign, "+0". */
    fun relativeAltitude(m: Double, unit: UnitInfo): String {
        val v = iround(unit.toUser(m))
        return if (v >= 0) "+$v" else "$v"
    }

    /**
     * FormatDistanceSmart(): 0 to 2 decimals; m or ft up to
     * [smallUnitThreshold] of them (XCSoar's default 0: only for 0).
     *
     * @return the text with its unit name, and the unit
     */
    fun distanceSmart(m: Double, unit: UnitInfo,
                      smallUnitThreshold: Double = 0.0): Pair<String, UnitInfo> {
        val small = smallerDistanceUnit(unit)
        val best = if (small.unit != unit.unit && small.toUser(m) <= smallUnitThreshold) small
                   else unit
        val value = best.toUser(m)
        val precision = when {
            value >= 100 -> 0
            value > 10 -> 1
            else -> 2
        }
        return "${fixed(value, precision)} ${best.name}" to best
    }

    private fun smallerDistanceUnit(unit: UnitInfo) = when (unit.unit) {
        UnitInfo.KILOMETER -> settings.unit(UnitInfo.METER)
        UnitInfo.NAUTICAL_MILES, UnitInfo.STATUTE_MILES -> settings.unit(UnitInfo.FEET)
        else -> unit
    }

    /** FormatSpeed(): with [precision], one decimal below 100. */
    fun speed(ms: Double, unit: UnitInfo, precision: Boolean): String {
        val value = unit.toUser(ms)
        return fixed(value, if (precision && value < 100) 1 else 0)
    }

    /** FormatVerticalSpeed(): one decimal (fpm: none). */
    fun verticalSpeed(ms: Double, unit: UnitInfo, sign: Boolean): String {
        val text = fixed(unit.toUser(ms), if (unit.unit == UnitInfo.FEET_PER_MINUTE) 0 else 1)
        return if (sign && !text.startsWith("-")) "+$text" else text
    }

    /** FormatWingLoading(): one decimal up to 20. */
    fun wingLoading(kgm2: Double, unit: UnitInfo): String {
        val value = unit.toUser(kgm2)
        return fixed(value, if (value > 20) 0 else 1)
    }

    /** FormatMass(): whole units. */
    fun mass(kg: Double, unit: UnitInfo) = iround(unit.toUser(kg)).toString()

    /** FormatTemperature(): whole degrees; [kelvin] in. */
    fun temperature(kelvin: Double, unit: UnitInfo) = fixed(unit.toUser(kelvin), 0)

    /** FormatPressure(): inHg with two decimals, others none. */
    fun pressure(hpa: Double, unit: UnitInfo) =
        fixed(unit.toUser(hpa), if (unit.unit == UnitInfo.INCH_MERCURY) 2 else 0)

    companion object {
        /** XCSoar's iround(): lround(), halves away from zero. */
        fun iround(x: Double): Int {
            val r = floor(abs(x) + 0.5)
            return (if (x < 0) -r else r).toInt()
        }

        /**
         * printf("%.Nf"): the exact binary value rounded half to even,
         * "-0.0" for small negative values, like glibc.
         */
        fun fixed(value: Double, decimals: Int): String {
            val text = BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
            val negative = value < 0 || (value == 0.0 && 1 / value < 0)
            return if (negative && !text.startsWith("-")) "-$text" else text
        }
    }
}
