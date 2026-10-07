// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilesTest {
    @Test
    fun parseList() {
        // as core/host/CoreProfiles.hpp documents it
        val profiles = ProfileInfo.parseList(
            """[{"name":"Club","path":"/d/profiles/Club.prf","current":false,"password":true},""" +
                """{"name":"default","path":"/d/profiles/default.prf","current":true,""" +
                """"password":false}]""")
        assertEquals(listOf(
            ProfileInfo("Club", "/d/profiles/Club.prf", current = false, password = true),
            ProfileInfo("default", "/d/profiles/default.prf", current = true, password = false),
        ), profiles)
    }
}
