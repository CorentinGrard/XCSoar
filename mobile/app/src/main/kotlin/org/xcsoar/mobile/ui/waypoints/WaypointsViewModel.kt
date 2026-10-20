// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.waypoints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
class WaypointsViewModel(
    private val core: XcsoarCore,
    filter: WaypointFilter = WaypointFilter.LANDABLE,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(WaypointsState(filter = filter))
    val state: StateFlow<WaypointsState> = stateFlow.asStateFlow()

    private var job: Job? = null

    /**
     * Shown: search now, then refresh the distances and arrival heights
     * every few seconds (they change in flight).  The order stays as the
     * first search made it until the search or the filter changes, like
     * upstream's list: a row never moves under the pilot's finger.
     */
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
            var first = true
            while (isActive) {
                val s = stateFlow.value
                try {
                    val found = core.searchWaypoints(s.query, s.filter, MAX_RESULTS)
                    stateFlow.update {
                        it.copy(waypoints = if (first) found else keepOrder(it.waypoints, found))
                    }
                    first = false
                } catch (e: CancellationException) {
                    // a newer search replaced this one
                    throw e
                } catch (e: Exception) {
                    stateFlow.update { it.copy(waypoints = emptyList(), error = e.message) }
                }
                delay(REFRESH_MS)
            }
        }
    }

    companion object {
        private const val MAX_RESULTS = 50
        private const val REFRESH_MS = 5000L

        /**
         * The rows of [shown] in their order, with the newer values of
         * [found]; a row [found] no longer has keeps its old values.  [found]
         * as it is when nothing is shown, or when [shown] was sorted by
         * name for want of a position and [found] has one: the first fix
         * sorts the list once, nearest first.
         */
        fun keepOrder(shown: List<WaypointInfo>?, found: List<WaypointInfo>):
                List<WaypointInfo> {
            if (shown.isNullOrEmpty() ||
                (shown.all { it.distance == null } && found.any { it.distance != null }))
                return found
            val newer = found.associateBy { it.id }
            return shown.map { newer[it.id] ?: it }
        }
    }
}
