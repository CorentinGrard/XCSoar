// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One IGC file XCSoar's logger wrote. */
data class FlightLog(
    val path: String,
    /** The file name, e.g. "2026-10-02-XCS-AAA-01.igc". */
    val name: String,
    /** Last written, ms since the epoch. */
    val modified: Long,
    /** Bytes. */
    val size: Long,
)

/** The recorded flights, newest first. */
class FlightsViewModel(private val list: suspend () -> List<FlightLog>) : ViewModel() {
    private val flightsFlow = MutableStateFlow<List<FlightLog>?>(null)
    /** `null` while loading. */
    val flights: StateFlow<List<FlightLog>?> = flightsFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            flightsFlow.value = try {
                list().sortedByDescending { it.modified }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
