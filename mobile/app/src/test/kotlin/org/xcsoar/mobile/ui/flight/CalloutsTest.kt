// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.TimeUnit
import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.core.CoreEvent
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.NextWaypoint
import org.xcsoar.mobile.core.Thermal
import org.xcsoar.mobile.core.XcsoarCore

/** What the voice says, and when (metric units, the default). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalloutsTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun warning(state: String, time: Double? = null, distance: Double? = null) =
        AirspaceWarningInfo("1", state, "GRENOBLE CTR", "CTR", time = time, distance = distance)

    @Test
    fun airspace() {
        assertEquals("Inside airspace: GRENOBLE CTR", Callouts.airspace(warning("inside")))
        assertEquals("Airspace ahead: GRENOBLE CTR, in 40 seconds",
                     Callouts.airspace(warning("near", time = 37.0)))
        assertEquals("Airspace ahead: GRENOBLE CTR, in 3 minutes",
                     Callouts.airspace(warning("near", time = 200.0, distance = 9000.0)))
        assertEquals("Airspace ahead: GRENOBLE CTR, 2.3 kilometres",
                     Callouts.airspace(warning("near", distance = 2300.0)))
        assertEquals("Airspace ahead: GRENOBLE CTR", Callouts.airspace(warning("near")))
        // crossed by the task: shown, not said
        assertNull(Callouts.airspace(warning("task")))
    }

    @Test
    fun finalGlide() {
        assertEquals("Above final glide by 123 metres", Callouts.finalGlide(true, 123.0))
        assertEquals("Below final glide by 80 metres", Callouts.finalGlide(false, -80.0))
        assertEquals("Above final glide", Callouts.finalGlide(true, null))
    }

    @Test
    fun taskPoints() {
        val next = NextWaypoint("Grenoble", 7300.0, 45.0, 120.0)
        assertEquals("Turn point reached. Next: Grenoble, 7.3 kilometres",
                     Callouts.taskPoint(GlideComputerEvent.TASK_NEXTWAYPOINT, next))
        assertEquals("Task started", Callouts.taskPoint(GlideComputerEvent.TASK_START, null))
        assertEquals("Task finished", Callouts.taskPoint(GlideComputerEvent.TASK_FINISH, next))
        assertNull(Callouts.taskPoint(GlideComputerEvent.LANDING, next))
    }

    @Test
    fun climbs() {
        assertEquals("Climb 2.1, gained 452", Callouts.climb(Thermal(2.14, 452.0, 300.0)))
        assertEquals("Climb -0.3, gained 0", Callouts.climb(Thermal(-0.3, 0.0, 60.0)))
        // one turn for traffic is no climb
        assertNull(Callouts.climb(Thermal(1.0, 20.0, 25.0)))
    }

    /** A core whose state and events the test sets. */
    private class Scripted(fake: FakeXcsoarCore) : XcsoarCore by fake {
        val states = MutableStateFlow<FlightState?>(null)
        val eventFlow = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 8)
        override val flightState: StateFlow<FlightState?> = states
        override val events: SharedFlow<CoreEvent> = eventFlow
    }

    @Test
    fun leavingAClimbSaysItsAverageOnce() = runTest {
        val core = Scripted(FakeXcsoarCore(backgroundScope))
        val viewModel = FlightViewModel(core) { "" }
        try {
            val said = mutableListOf<String>()
            backgroundScope.launch { viewModel.callouts.collect { said += it } }

            val s = FakeXcsoarCore.syntheticState(0)
            core.states.value = s.copy(circling = true)
            advanceTimeBy(1_000)
            core.states.value = s.copy(circling = false, lastThermal = Thermal(2.1, 450.0, 300.0))
            advanceTimeBy(2_000)
            // straight back into a climb and out: too soon for another callout
            core.states.value = s.copy(circling = true)
            core.states.value = s.copy(circling = false, lastThermal = Thermal(1.5, 200.0, 90.0))
            advanceTimeBy(2_000)

            assertEquals(listOf("Climb 2.1, gained 450"), said)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun turningInAndOutOfAClimbSaysItOnce() = runTest {
        val core = Scripted(FakeXcsoarCore(backgroundScope))
        val viewModel = FlightViewModel(core) { "" }
        try {
            val said = mutableListOf<String>()
            backgroundScope.launch { viewModel.callouts.collect { said += it } }

            val s = FakeXcsoarCore.syntheticState(0)
            val thermal = Thermal(0.5, 157.0, 300.0)
            for (i in 1..3) {
                core.states.value = s.copy(circling = true, lastThermal = thermal.takeIf { i > 1 })
                advanceTimeBy(1_000)
                core.states.value = s.copy(circling = false, lastThermal = thermal)
                // longer than the gap between callouts
                advanceTimeBy(10_000)
                ShadowSystemClock.advanceBy(10, TimeUnit.SECONDS)
            }
            assertEquals(listOf("Climb 0.5, gained 157"), said)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun turnPointSaysTheNextOne() = runTest {
        val core = Scripted(FakeXcsoarCore(backgroundScope))
        var settings = CalloutSettings(taskPoints = false)
        val viewModel = FlightViewModel(core, { settings }) { "" }
        try {
            val said = mutableListOf<String>()
            backgroundScope.launch { viewModel.callouts.collect { said += it } }

            core.states.value = FakeXcsoarCore.syntheticState(0)
            core.eventFlow.emit(CoreEvent.GlideComputer(GlideComputerEvent.TASK_NEXTWAYPOINT))
            advanceTimeBy(2_000)
            assertEquals(emptyList<String>(), said)

            settings = CalloutSettings()
            core.eventFlow.emit(CoreEvent.GlideComputer(GlideComputerEvent.TASK_NEXTWAYPOINT))
            // the snapshot after the event has the next point
            core.states.value = FakeXcsoarCore.syntheticState(1)
                .copy(next = NextWaypoint("Grenoble", 23_400.0, 45.0, 120.0))
            advanceTimeBy(2_000)
            assertEquals(listOf("Turn point reached. Next: Grenoble, 23.4 kilometres"), said)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
