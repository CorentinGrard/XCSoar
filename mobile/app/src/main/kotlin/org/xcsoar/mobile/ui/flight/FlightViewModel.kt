// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.CoreEvent
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.XcsoarCore
import kotlin.math.roundToLong

/**
 * State and actions of the flight screen; knows only [XcsoarCore].
 *
 * @param core the process's core (shared with the other screens)
 * @param demoFlight path of a recorded flight for [replayDemo]
 */
class FlightViewModel(
    private val core: XcsoarCore,
    private val demoFlight: () -> String,
) : ViewModel() {
    val flightState: StateFlow<FlightState?> = core.flightState

    private val lastEventFlow = MutableStateFlow<String?>(null)
    /** A short text for the latest glide computer event, for the status line. */
    val lastEvent: StateFlow<String?> = lastEventFlow.asStateFlow()

    /* the pilot's choice of layout, until the next flight mode change */
    private val modeOverride = MutableStateFlow<Boolean?>(null)

    /** Show the circling layout: the glide computer's mode, or the pilot's pick. */
    val showCircling: StateFlow<Boolean> =
        combine(flightState, modeOverride) { state, override ->
            override ?: (state?.circling == true)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            try {
                core.start()
            } catch (e: Exception) {
                // never crash the app: show why and keep the UI usable
                lastEventFlow.value = "Core failed to start: ${e.message}"
            }
        }
        viewModelScope.launch {
            core.events.collect { event ->
                if (event is CoreEvent.GlideComputer &&
                    (event.event == GlideComputerEvent.FLIGHTMODE_CLIMB ||
                     event.event == GlideComputerEvent.FLIGHTMODE_CRUISE))
                    modeOverride.value = null
                lastEventFlow.value = when (event) {
                    is CoreEvent.GlideComputer -> describe(event.event)
                    is CoreEvent.Message -> event.text
                    CoreEvent.ReplayFinished -> "Replay finished"
                }
            }
        }
    }

    /** Replay the bundled demo flight at 10× real time. */
    fun replayDemo() {
        viewModelScope.launch {
            try {
                core.startReplay(demoFlight(), timeScale = 10.0)
            } catch (e: Exception) {
                lastEventFlow.value = "Replay failed: ${e.message}"
            }
        }
    }

    fun stopReplay() {
        viewModelScope.launch {
            try {
                core.stopReplay()
            } catch (e: Exception) {
                lastEventFlow.value = "Replay not stopped: ${e.message}"
            }
        }
    }

    /** Show the cruise or circling layout until the next flight mode change. */
    fun selectFlightMode(circling: Boolean) {
        modeOverride.value = circling
    }

    /** Whether the core draws XCSoar's map (else the screen shows a placeholder). */
    val hasMap: Boolean get() = core.hasMap

    private var mapAttach: Job? = null

    fun attachMap(surface: Any, width: Int, height: Int, dpi: Int) {
        mapAttach?.cancel()
        mapAttach = viewModelScope.launch {
            try {
                core.attachMap(surface, width, height, dpi)
            } catch (e: Exception) {
                lastEventFlow.value = "Map failed: ${e.message}"
            }
        }
    }

    /** Blocks until the core stopped drawing (the surface goes away next). */
    fun detachMap() {
        mapAttach?.cancel()
        mapAttach = null
        core.detachMap()
    }

    fun setMapAircraftPosition(x: Int, y: Int) {
        viewModelScope.launch {
            try {
                core.setMapAircraftPosition(x, y)
            } catch (_: Exception) {
                // not attached yet; the core keeps the centre
            }
        }
    }

    fun zoomMap(steps: Int) {
        viewModelScope.launch {
            try {
                core.zoomMap(steps)
            } catch (_: Exception) {
            }
        }
    }

    /** MacCready ± 0.1 m/s, clamped to 0..5 like XCSoar. */
    fun changeMacCready(delta: Double) {
        val current = flightState.value?.macCready ?: return
        val mc = ((current + delta) * 10).roundToLong() / 10.0
        viewModelScope.launch {
            try {
                core.setMacCready(mc.coerceIn(0.0, 5.0))
            } catch (e: Exception) {
                lastEventFlow.value = "MacCready not set: ${e.message}"
            }
        }
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
