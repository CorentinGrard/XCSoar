// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the bytes of `xcs_flight_snapshot` (core/api/xcsoar_core.h).
 *
 * The offsets below are part of the C ABI.  They are locked on the C++
 * side by static_asserts in core/api/XcsoarCore.cpp; change both together.
 */
object SnapshotDecoder {
    const val API_VERSION = 1
    const val SIZE = 280

    // xcs_flight_snapshot field offsets
    private const val STRUCT_SIZE = 0
    private const val API_VERSION_OFFSET = 4
    private const val SEQUENCE = 8
    private const val VALID = 16
    private const val FLAGS = 20
    private const val TIME_UTC = 24
    private const val FLIGHT_TIME = 32
    private const val LATITUDE = 40
    private const val LONGITUDE = 48
    private const val TRACK = 56
    private const val GROUND_SPEED = 64
    private const val TRUE_AIRSPEED = 72
    private const val INDICATED_AIRSPEED = 80
    private const val GPS_ALTITUDE = 88
    private const val BARO_ALTITUDE = 96
    private const val NAV_ALTITUDE = 104
    private const val TERRAIN_ALTITUDE = 112
    private const val ALTITUDE_AGL = 120
    private const val VARIO = 128
    private const val AVERAGE_VARIO = 136
    private const val NETTO_VARIO = 144
    private const val WIND_SPEED = 152
    private const val WIND_BEARING = 160
    private const val MAC_CREADY = 168
    private const val NEXT_DISTANCE = 176
    private const val NEXT_BEARING = 184
    private const val NEXT_ALTITUDE_DIFFERENCE = 192
    private const val TASK_REMAINING_DISTANCE = 200
    private const val FINAL_GLIDE_ALTITUDE_DIFFERENCE = 208
    private const val NEXT_NAME = 216
    private const val NEXT_NAME_SIZE = 64

    // XCS_VALID_* bits
    const val VALID_TIME = 1 shl 0
    const val VALID_LOCATION = 1 shl 1
    const val VALID_TRACK = 1 shl 2
    const val VALID_GROUND_SPEED = 1 shl 3
    const val VALID_AIRSPEED = 1 shl 4
    const val VALID_GPS_ALTITUDE = 1 shl 5
    const val VALID_BARO_ALTITUDE = 1 shl 6
    const val VALID_NAV_ALTITUDE = 1 shl 7
    const val VALID_TERRAIN = 1 shl 8
    const val VALID_VARIO = 1 shl 9
    const val VALID_NETTO_VARIO = 1 shl 10
    const val VALID_WIND = 1 shl 11
    const val VALID_TASK = 1 shl 12
    const val VALID_NEXT_WAYPOINT = 1 shl 13
    const val VALID_FINAL_GLIDE = 1 shl 14

    // XCS_FLAG_* bits
    const val FLAG_GPS_REAL = 1 shl 0
    const val FLAG_FLYING = 1 shl 1
    const val FLAG_CIRCLING = 1 shl 2
    const val FLAG_FINAL_GLIDE = 1 shl 3
    const val FLAG_REPLAY = 1 shl 4

    /**
     * Decode one snapshot.  Reads [SIZE] bytes starting at the buffer's
     * position, without changing it.
     *
     * @throws IllegalArgumentException if the data is not a compatible
     * snapshot
     */
    fun decode(buffer: ByteBuffer): FlightState {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val base = b.position()
        require(b.remaining() >= SIZE) { "snapshot too short: ${b.remaining()} bytes" }

        val structSize = b.getInt(base + STRUCT_SIZE)
        require(structSize >= SIZE) { "snapshot struct_size $structSize < $SIZE" }
        val apiVersion = b.getInt(base + API_VERSION_OFFSET)
        require(apiVersion == API_VERSION) { "snapshot api_version $apiVersion" }

        val valid = b.getInt(base + VALID)
        val flags = b.getInt(base + FLAGS)

        fun d(offset: Int) = b.getDouble(base + offset)
        fun ifValid(bit: Int, offset: Int) = if (valid and bit != 0) d(offset) else null

        return FlightState(
            sequence = b.getLong(base + SEQUENCE),
            timeUtc = ifValid(VALID_TIME, TIME_UTC),
            flightTime = d(FLIGHT_TIME),
            position = if (valid and VALID_LOCATION != 0)
                GeoPosition(d(LATITUDE), d(LONGITUDE)) else null,
            track = ifValid(VALID_TRACK, TRACK),
            groundSpeed = ifValid(VALID_GROUND_SPEED, GROUND_SPEED),
            trueAirspeed = ifValid(VALID_AIRSPEED, TRUE_AIRSPEED),
            indicatedAirspeed = ifValid(VALID_AIRSPEED, INDICATED_AIRSPEED),
            gpsAltitude = ifValid(VALID_GPS_ALTITUDE, GPS_ALTITUDE),
            baroAltitude = ifValid(VALID_BARO_ALTITUDE, BARO_ALTITUDE),
            navAltitude = ifValid(VALID_NAV_ALTITUDE, NAV_ALTITUDE),
            terrainAltitude = ifValid(VALID_TERRAIN, TERRAIN_ALTITUDE),
            altitudeAgl = ifValid(VALID_TERRAIN, ALTITUDE_AGL),
            vario = ifValid(VALID_VARIO, VARIO),
            averageVario = ifValid(VALID_VARIO, AVERAGE_VARIO),
            nettoVario = ifValid(VALID_NETTO_VARIO, NETTO_VARIO),
            wind = if (valid and VALID_WIND != 0)
                Wind(d(WIND_SPEED), d(WIND_BEARING)) else null,
            macCready = d(MAC_CREADY),
            next = if (valid and VALID_NEXT_WAYPOINT != 0)
                NextWaypoint(
                    name = readCString(b, base + NEXT_NAME, NEXT_NAME_SIZE),
                    distance = d(NEXT_DISTANCE),
                    bearing = d(NEXT_BEARING),
                    altitudeDifference = d(NEXT_ALTITUDE_DIFFERENCE),
                ) else null,
            finalGlide = if (valid and VALID_FINAL_GLIDE != 0)
                FinalGlide(d(TASK_REMAINING_DISTANCE), d(FINAL_GLIDE_ALTITUDE_DIFFERENCE))
            else null,
            gpsReal = flags and FLAG_GPS_REAL != 0,
            flying = flags and FLAG_FLYING != 0,
            circling = flags and FLAG_CIRCLING != 0,
            aboveFinalGlide = flags and FLAG_FINAL_GLIDE != 0,
            replay = flags and FLAG_REPLAY != 0,
        )
    }

    /** A zero-terminated UTF-8 string in a fixed-size field. */
    private fun readCString(b: ByteBuffer, offset: Int, size: Int): String {
        var length = 0
        while (length < size && b.get(offset + length) != 0.toByte())
            length++
        val bytes = ByteArray(length)
        for (i in 0 until length)
            bytes[i] = b.get(offset + i)
        return bytes.toString(Charsets.UTF_8)
    }
}
