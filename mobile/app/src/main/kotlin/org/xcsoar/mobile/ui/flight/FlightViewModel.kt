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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.CoreEvent
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.MapOrientation
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
    /**
     * The latest state, at most [UI_INTERVAL_MS] apart: the core
     * publishes on every sensor update (about ten per second), more than
     * the screen needs and costly to recompose each time.
     */
    val flightState: StateFlow<FlightState?> = core.flightState  // conflated
        .transform { emit(it); delay(UI_INTERVAL_MS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, core.flightState.value)

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
                mapOrientationFlow.value = core.mapOrientation()
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

    private val mapFollowsFlow = MutableStateFlow(true)
    /** Whether the map follows the aircraft (false after the pilot panned it). */
    val mapFollows: StateFlow<Boolean> = mapFollowsFlow.asStateFlow()

    /* finger movement not yet sent to the core: events arrive faster
       than the core draws, so they are added up and sent in one go */
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingScale = 1f
    private var gestureJob: Job? = null

    /** A pan ([dx], [dy] pixels) and pinch ([zoom] > 1 = in) on the map. */
    fun mapGesture(dx: Float, dy: Float, zoom: Float) {
        pendingDx += dx
        pendingDy += dy
        pendingScale *= zoom
        if (dx != 0f || dy != 0f)
            mapFollowsFlow.value = false
        if (gestureJob?.isActive == true)
            return
        gestureJob = viewModelScope.launch {
            while (pendingDx != 0f || pendingDy != 0f || pendingScale != 1f) {
                val x = pendingDx
                val y = pendingDy
                val z = pendingScale
                pendingDx = 0f
                pendingDy = 0f
                pendingScale = 1f
                try {
                    if (z != 1f) core.scaleMap(z)
                    if (x != 0f || y != 0f) core.panMap(x, y)
                } catch (_: Exception) {
                    // not attached (yet): nothing to move
                }
            }
        }
    }

    private val mapOrientationFlow = MutableStateFlow<MapOrientation?>(null)
    /** The map orientation; null until the core answered. */
    val mapOrientation: StateFlow<MapOrientation?> = mapOrientationFlow.asStateFlow()

    /** North up → track up → target up → north up. */
    fun cycleMapOrientation() {
        val next = when (mapOrientationFlow.value) {
            MapOrientation.NORTH_UP -> MapOrientation.TRACK_UP
            MapOrientation.TRACK_UP -> MapOrientation.TARGET_UP
            else -> MapOrientation.NORTH_UP
        }
        mapOrientationFlow.value = next
        viewModelScope.launch {
            try {
                core.setMapOrientation(next)
            } catch (_: Exception) {
            }
        }
    }

    private val mapItemsFlow = MutableStateFlow<List<MapItemInfo>?>(null)
    /** The items at the last held point of the map; null when none is shown. */
    val mapItems: StateFlow<List<MapItemInfo>?> = mapItemsFlow.asStateFlow()

    /** The pilot held a point of the map (pixels). */
    fun showMapItems(x: Int, y: Int) {
        viewModelScope.launch {
            mapItemsFlow.value = try {
                core.mapItemsAt(x, y)
            } catch (e: Exception) {
                lastEventFlow.value = "Map items: ${e.message}"
                null
            }
        }
    }

    fun hideMapItems() {
        mapItemsFlow.value = null
    }

    fun followMap() {
        mapFollowsFlow.value = true
        viewModelScope.launch {
            try {
                core.followMap()
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

/** 5 screen updates per second: smooth enough for the vario. */
private const val UI_INTERVAL_MS = 200L
