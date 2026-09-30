// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.CoreEvent
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.XcsoarCore
import kotlin.math.roundToLong

/**
 * State and actions of the flight screen; knows only [XcsoarCore].
 *
 * @param createCore creates the core; gets this view model's scope
 * (used by [org.xcsoar.mobile.core.FakeXcsoarCore])
 */
class FlightViewModel(createCore: (CoroutineScope) -> XcsoarCore) : ViewModel() {
    private val core = createCore(viewModelScope)

    val flightState: StateFlow<FlightState?> = core.flightState

    private val lastEventFlow = MutableStateFlow<String?>(null)
    /** A short text for the latest glide computer event, for the status line. */
    val lastEvent: StateFlow<String?> = lastEventFlow.asStateFlow()

    init {
        viewModelScope.launch { core.start() }
        viewModelScope.launch {
            core.events.collect { event ->
                lastEventFlow.value = when (event) {
                    is CoreEvent.GlideComputer -> describe(event.event)
                    is CoreEvent.Message -> event.text
                    CoreEvent.ReplayFinished -> "Replay finished"
                }
            }
        }
    }

    /** MacCready ± 0.1 m/s, clamped to 0..5 like XCSoar. */
    fun changeMacCready(delta: Double) {
        val current = flightState.value?.macCready ?: return
        val mc = ((current + delta) * 10).roundToLong() / 10.0
        viewModelScope.launch { core.setMacCready(mc.coerceIn(0.0, 5.0)) }
    }

    private fun describe(e: GlideComputerEvent): String? = when (e) {
        GlideComputerEvent.TAKEOFF -> "Take-off"
        GlideComputerEvent.LANDING -> "Landing"
        GlideComputerEvent.FLIGHTMODE_CLIMB -> "Climb"
        GlideComputerEvent.FLIGHTMODE_CRUISE -> "Cruise"
        GlideComputerEvent.FLIGHTMODE_FINALGLIDE -> "Final glide"
        GlideComputerEvent.TASK_START -> "Task started"
        GlideComputerEvent.TASK_NEXTWAYPOINT -> "Next turnpoint"
        GlideComputerEvent.TASK_FINISH -> "Task finished"
        GlideComputerEvent.LANDABLE_UNREACHABLE -> "No landable in reach"
        else -> null
    }
}
