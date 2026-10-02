// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.xcsoar.mobile.core.RepositoryFile

class DownloadStateTest {
    private val files = listOf(
        RepositoryFile("AF-ASP-National-OpenAIP.txt", "u", "airspace", "af",
                       "Afghanistan Airspace from OpenAIP"),
        RepositoryFile("FR-ASP-National-OpenAIP.txt", "u", "airspace", "fr",
                       "France Airspace from OpenAIP"),
        RepositoryFile("france.txt", "u", "airspace", "", "Airspace of France"),
    )

    private fun shown(query: String) =
        DownloadState(files = files, query = query).shown.map { it.name }

    @Test
    fun shortQueryIsCountryOrNamePrefix() {
        assertEquals(listOf("FR-ASP-National-OpenAIP.txt", "france.txt"), shown("fr"))
        assertEquals(listOf("AF-ASP-National-OpenAIP.txt"), shown("AF"))
    }

    @Test
    fun longQuerySearchesNamesAndDescriptions() {
        assertEquals(listOf("FR-ASP-National-OpenAIP.txt", "france.txt"), shown("france"))
        assertEquals(3, shown("").size)
    }
}
