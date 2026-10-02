// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** L3: decoding of xcs_flight_snapshot, and agreement with the C header. */
class SnapshotDecoderTest {
    private fun snapshot(valid: Int, flags: Int = 0, name: String = ""): ByteBuffer {
        val b = ByteBuffer.allocateDirect(SnapshotDecoder.SIZE).order(ByteOrder.nativeOrder())
        b.putInt(0, SnapshotDecoder.SIZE)
        b.putInt(4, SnapshotDecoder.API_VERSION)
        b.putLong(8, 42)
        b.putInt(16, valid)
        b.putInt(20, flags)
        // doubles 24..208: field index * 10 + 1, so each one is recognisable
        for (i in 0 until 24)
            b.putDouble(24 + i * 8, i * 10.0 + 1)
        val bytes = name.toByteArray(Charsets.UTF_8)
        for (i in bytes.indices)
            b.put(216 + i, bytes[i])
        // doubles 280..360: 1000 + field index
        for (i in 0 until 11)
            b.putDouble(280 + i * 8, 1000.0 + i)
        // doubles 368..392: 2000 + field index
        for (i in 0 until 4)
            b.putDouble(368 + i * 8, 2000.0 + i)
        return b
    }

    @Test
    fun decodesAllValidFields() {
        val s = SnapshotDecoder.decode(snapshot(0x3fffff, 0x1f, "Saint-Crépin"))

        assertEquals(42L, s.sequence)
        assertEquals(1.0, s.timeUtc!!, 0.0)
        assertEquals(11.0, s.flightTime, 0.0)
        assertEquals(GeoPosition(21.0, 31.0), s.position)
        assertEquals(41.0, s.track!!, 0.0)
        assertEquals(51.0, s.groundSpeed!!, 0.0)
        assertEquals(61.0, s.trueAirspeed!!, 0.0)
        assertEquals(71.0, s.indicatedAirspeed!!, 0.0)
        assertEquals(81.0, s.gpsAltitude!!, 0.0)
        assertEquals(91.0, s.baroAltitude!!, 0.0)
        assertEquals(101.0, s.navAltitude!!, 0.0)
        assertEquals(111.0, s.terrainAltitude!!, 0.0)
        assertEquals(121.0, s.altitudeAgl!!, 0.0)
        assertEquals(131.0, s.vario!!, 0.0)
        assertEquals(141.0, s.averageVario!!, 0.0)
        assertEquals(151.0, s.nettoVario!!, 0.0)
        assertEquals(Wind(161.0, 171.0), s.wind)
        assertEquals(181.0, s.macCready, 0.0)
        assertEquals(NextWaypoint("Saint-Crépin", 191.0, 201.0, 211.0), s.next)
        assertEquals(FinalGlide(221.0, 231.0), s.finalGlide)
        assertTrue(s.gpsReal && s.flying && s.circling && s.aboveFinalGlide && s.replay)
        assertEquals(1000.0, s.speedToFly!!, 0.0)
        assertEquals(1001.0, s.ld!!, 0.0)
        assertEquals(1002.0, s.ldRequired!!, 0.0)
        assertEquals(1003.0, s.nextTimeRemaining!!, 0.0)
        assertEquals(1004.0, s.taskSpeed!!, 0.0)
        assertEquals(Thermal(1005.0, 1006.0, 1007.0), s.currentThermal)
        assertEquals(Thermal(1008.0, 1009.0, 1010.0), s.lastThermal)
        assertEquals(2000.0, s.ballast, 0.0)
        assertEquals(2001.0, s.maxBallast, 0.0)
        assertEquals(2002.0, s.bugs, 0.0)
        assertEquals(2003.0, s.wingLoading!!, 0.0)
    }

    @Test
    fun invalidFieldsAreNull() {
        val s = SnapshotDecoder.decode(snapshot(valid = 0, flags = 0))

        assertNull(s.timeUtc)
        assertNull(s.position)
        assertNull(s.navAltitude)
        assertNull(s.vario)
        assertNull(s.wind)
        assertNull(s.next)
        assertNull(s.finalGlide)
        assertNull(s.speedToFly)
        assertNull(s.currentThermal)
        // always valid
        assertEquals(181.0, s.macCready, 0.0)
        assertFalse(s.flying)
    }

    @Test
    fun honoursBufferPosition() {
        val big = ByteBuffer.allocateDirect(SnapshotDecoder.SIZE + 16).order(ByteOrder.nativeOrder())
        big.position(16)
        big.put(snapshot(SnapshotDecoder.VALID_VARIO))
        big.position(16)

        val s = SnapshotDecoder.decode(big)
        assertEquals(131.0, s.vario!!, 0.0)
        assertEquals(16, big.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherApiVersion() {
        val b = snapshot(0)
        b.putInt(4, SnapshotDecoder.API_VERSION + 1)
        SnapshotDecoder.decode(b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortBuffer() {
        SnapshotDecoder.decode(ByteBuffer.allocate(100))
    }

    /* The constants must match core/api/xcsoar_core.h. */

    @Test
    fun glideComputerEventsMatchHeader() {
        val c = CoreHeader.constants("XCS_GCE_")
        assertEquals(GlideComputerEvent.entries.size, c.size)
        for (e in GlideComputerEvent.entries)
            assertEquals(e.name, c["XCS_GCE_${e.name}"], e.code)
    }

    @Test
    fun validityAndFlagBitsMatchHeader() {
        val valid = CoreHeader.constants("XCS_VALID_")
        val flags = CoreHeader.constants("XCS_FLAG_")
        val kotlin = SnapshotDecoder::class.java.declaredFields
            .filter { it.name.startsWith("VALID_") || it.name.startsWith("FLAG_") }
            .associate { it.isAccessible = true; "XCS_${it.name}" to it.getInt(null) }

        assertEquals(valid + flags, kotlin)
    }

    @Test
    fun eventTypesMatchHeader() {
        val c = CoreHeader.constants("XCS_EVENT_")
        val kotlin = CoreEventType::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && it.name != "INSTANCE" }
            .associate { it.isAccessible = true; "XCS_EVENT_${it.name}" to it.getInt(null) }
        assertEquals(c, kotlin)
    }

    @Test
    fun apiVersionMatchesHeader() {
        val version = Regex("""#define XCS_API_VERSION (\d+)""").find(CoreHeader.text)!!.groupValues[1]
        assertEquals(version.toInt(), SnapshotDecoder.API_VERSION)
    }
}
