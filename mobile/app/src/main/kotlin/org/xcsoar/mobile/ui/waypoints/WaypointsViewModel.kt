// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.waypoints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.WaypointFilter
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.core.XcsoarCore

data class WaypointsState(
    val query: String = "",
    val filter: WaypointFilter = WaypointFilter.LANDABLE,
    /** null until the core answered. */
    val waypoints: List<WaypointInfo>? = null,
    val error: String? = null,
    /** Set after a successful "Go to": the screen closes. */
    val done: Boolean = false,
)

/** XCSoar's waypoint list: nearest first, by name and type. */
class WaypointsViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(WaypointsState())
    val state: StateFlow<WaypointsState> = stateFlow.asStateFlow()

    private var job: Job? = null

    /** Shown: search now and again every few seconds (distances change in flight). */
    fun open() {
        stateFlow.update { it.copy(done = false, error = null) }
        restart()
    }

    fun close() {
        job?.cancel()
    }

    fun search(query: String) {
        stateFlow.update { it.copy(query = query) }
        restart()
    }

    fun filter(filter: WaypointFilter) {
        stateFlow.update { it.copy(filter = filter) }
        restart()
    }

    fun goto(waypoint: WaypointInfo) {
        viewModelScope.launch {
            try {
                core.gotoWaypoint(waypoint.id)
                job?.cancel()
                stateFlow.update { it.copy(done = true) }
            } catch (_: Exception) {
                stateFlow.update {
                    it.copy(error = "${waypoint.name}: go to only to landable waypoints " +
                        "(profile setting)")
                }
            }
        }
    }

    private fun restart() {
        job?.cancel()
        job = viewModelScope.launch {
            while (isActive) {
                val s = stateFlow.value
                try {
                    val found = core.searchWaypoints(s.query, s.filter, MAX_RESULTS)
                    stateFlow.update { it.copy(waypoints = found) }
                } catch (e: Exception) {
                    stateFlow.update { it.copy(waypoints = emptyList(), error = e.message) }
                }
                delay(REFRESH_MS)
            }
        }
    }

    private companion object {
        const val MAX_RESULTS = 50
        const val REFRESH_MS = 5000L
    }
}
