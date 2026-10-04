// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.xcsoar.mobile.core.XcsoarCore

/**
 * MacCready shows the pilot's value at once, before the core has it,
 * so quick steps build on each other instead of on the old value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacCreadyTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun shownBeforeTheCoreHasIt() = runTest {
        val fake = FakeXcsoarCore(backgroundScope)
        // the core takes the value only when the test says so
        val gate = CompletableDeferred<Unit>()
        val core = object : XcsoarCore by fake {
            override suspend fun setMacCready(macCready: Double) {
                gate.await()
                fake.setMacCready(macCready)
            }
        }
        val viewModel = FlightViewModel(core) { "" }
        advanceTimeBy(2_000)
        val before = fake.flightState.value?.macCready

        viewModel.setMacCready(2.0)
        viewModel.setMacCready(2.5)
        assertEquals(2.5, viewModel.macCready.value)
        assertEquals(before, fake.flightState.value?.macCready)

        gate.complete(Unit)
        assertEquals(2.5, fake.flightState.value?.macCready)
        assertEquals(2.5, viewModel.macCready.value)
        // its polling never ends; runTest would run it for ever
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun outOfRangeIsClamped() = runTest {
        val fake = FakeXcsoarCore(backgroundScope)
        val viewModel = FlightViewModel(fake) { "" }
        advanceTimeBy(2_000)

        viewModel.setMacCready(7.0)
        assertEquals(5.0, viewModel.macCready.value)
        assertEquals(5.0, fake.flightState.value?.macCready)
        viewModel.viewModelScope.cancel()
    }
}
