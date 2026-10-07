// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.WeGlideFlight
import org.xcsoar.mobile.core.XcsoarCore

/** Uploads to WeGlide are remembered across sessions, by file name. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FlightsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val old = FlightLog("/d/logs/2026-09-27-XCS-AAA-01.igc",
                                "2026-09-27-XCS-AAA-01.igc", 1, 1)
    private val new = FlightLog("/d/logs/2026-10-02-XCS-AAA-01.igc",
                                "2026-10-02-XCS-AAA-01.igc", 2, 1)
    private val oldFlight = WeGlideFlight(1, "https://www.weglide.org/flight/1")
    private val newFlight = WeGlideFlight(2, "https://www.weglide.org/flight/2")

    private class MemoryStore(val saved: MutableMap<String, WeGlideFlight>) : UploadStore {
        override suspend fun load() = saved.toMap()
        override suspend fun save(name: String, flight: WeGlideFlight) {
            saved[name] = flight
        }
    }

    @Test
    fun rememberedAndSaved() = runTest {
        val store = MemoryStore(mutableMapOf(old.name to oldFlight))
        val core = object : XcsoarCore by FakeXcsoarCore(backgroundScope) {
            override suspend fun uploadToWeGlide(igcPath: String) = newFlight
        }
        val viewModel = FlightsViewModel({ listOf(old, new) }, core, store)

        viewModel.refresh()
        // an earlier session's upload, not one of this flight
        assertEquals(mapOf(old.path to Upload.Done(oldFlight)), viewModel.uploads.value)

        viewModel.upload(new)
        assertEquals(Upload.Done(newFlight), viewModel.uploads.value[new.path])
        assertEquals(mapOf(old.name to oldFlight, new.name to newFlight), store.saved)
    }
}
