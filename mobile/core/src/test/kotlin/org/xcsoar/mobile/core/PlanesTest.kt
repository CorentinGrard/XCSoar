// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L3: the JSON of the plane, crew and WeGlide functions. */
class PlanesTest {
    private fun list(active: String, lastFlown: String) = PlaneList.parse("""
        {"active":"$active","last_flown":"$lastFlown","planes":[
          {"path":"/d/planes/D-1234.xcp","registration":"D-1234","competition_id":"XY",
           "type":"LS 4","polar_name":"LS-4","weglide_type":160,"double_seater":false},
          {"path":"/d/planes/D-5678.xcp","registration":"D-5678","competition_id":"",
           "type":"Duo Discus","polar_name":"Duo Discus","weglide_type":61,
           "double_seater":true}]}""")

    @Test
    fun parsesPlanes() {
        val l = list("/d/planes/D-1234.xcp", "")
        assertEquals(2, l.planes.size)
        assertEquals(PlaneInfo("/d/planes/D-5678.xcp", "D-5678", "", "Duo Discus",
                               "Duo Discus", 61, true), l.planes[1])
    }

    @Test
    fun suggestsTheLastPlaneFlown() {
        assertEquals("D-5678", list("/d/planes/D-1234.xcp", "/d/planes/D-5678.xcp")
            .suggested?.registration)
        // never flown: the active plane
        assertEquals("D-1234", list("/d/planes/D-1234.xcp", "").suggested?.registration)
        // neither: nothing preselected among several
        assertNull(list("", "").suggested)
        // a single plane is the obvious choice
        assertEquals("D-1", PlaneList.parse(
            """{"planes":[{"path":"/p","registration":"D-1"}]}""").suggested?.registration)
    }

    @Test
    fun parsesCrew() {
        val c = Crew.parse("""{"pilot":"Jane Doe","copilot":"","copilots":["John Roe"]}""")
        assertEquals(Crew("Jane Doe", "", listOf("John Roe")), c)
    }

    @Test
    fun weGlideSettings() {
        assertTrue(WeGlideSettings.parse(
            """{"enabled":true,"pilot_id":1234,"birthdate":"1980-05-31"}""").isConfigured)
        assertFalse(WeGlideSettings(true, 1234, "").isConfigured)
        assertFalse(WeGlideSettings(false, 1234, "1980-05-31").isConfigured)
    }

    @Test
    fun weGlideAnswers() {
        val flight = WeGlideFlight.parse("""{"flight_id":123456,
            "url":"https://www.weglide.org/flight/123456","date":"2026-10-02",
            "pilot":"Jane Doe","aircraft":"LS 4","registration":"D-1234",
            "competition_id":"XY"}""")
        assertEquals(123456L, flight.flightId)
        assertEquals("https://www.weglide.org/flight/123456", flight.url)

        val a = WeGlideAircraft.parse(
            """{"id":61,"name":"Duo Discus","double_seater":true,"kind":"GL","sc_class":"DO"}""")
        assertTrue(a.doubleSeater)
        assertEquals(listOf(WeGlideAircraft(160, "LS 4")),
                     WeGlideAircraft.parseList("""[{"id":160,"name":"LS 4"}]"""))
    }

    @Test(expected = WeGlideException::class)
    fun weGlideErrorsThrow() {
        WeGlideFlight.parse("""{"error":"422: already uploaded"}""")
    }

    @Test
    fun weGlideErrorKeepsTheMessage() {
        try {
            WeGlideAircraft.parse("""{"error":"No network"}""")
        } catch (e: WeGlideException) {
            assertEquals("No network", e.message)
        }
    }
}
