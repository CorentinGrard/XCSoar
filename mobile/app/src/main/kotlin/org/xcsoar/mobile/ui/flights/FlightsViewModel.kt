// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.WeGlideFlight
import org.xcsoar.mobile.core.XcsoarCore

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

/** How the upload of one flight to WeGlide went. */
sealed interface Upload {
    data object Running : Upload
    data class Done(val flight: WeGlideFlight) : Upload
    data class Failed(val message: String) : Upload
}

/** The recorded flights, newest first, and their WeGlide uploads. */
class FlightsViewModel(
    private val list: suspend () -> List<FlightLog>,
    private val core: XcsoarCore? = null,
) : ViewModel() {
    private val flightsFlow = MutableStateFlow<List<FlightLog>?>(null)
    /** `null` while loading. */
    val flights: StateFlow<List<FlightLog>?> = flightsFlow.asStateFlow()

    private val weGlideFlow = MutableStateFlow(false)
    /** WeGlide is set up: flights can be uploaded. */
    val weGlideReady: StateFlow<Boolean> = weGlideFlow.asStateFlow()

    private val uploadsFlow = MutableStateFlow<Map<String, Upload>>(emptyMap())
    /** Uploads of this session, by IGC path. */
    val uploads: StateFlow<Map<String, Upload>> = uploadsFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            flightsFlow.value = try {
                list().sortedByDescending { it.modified }
            } catch (_: Exception) {
                emptyList()
            }
            weGlideFlow.value = try {
                core?.weGlideSettings()?.isConfigured == true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun upload(flight: FlightLog) {
        val core = core ?: return
        if (uploadsFlow.value[flight.path] == Upload.Running) return
        uploadsFlow.update { it + (flight.path to Upload.Running) }
        viewModelScope.launch {
            val result = try {
                Upload.Done(core.uploadToWeGlide(flight.path))
            } catch (e: Exception) {
                Upload.Failed(e.message ?: "Upload failed")
            }
            uploadsFlow.update { it + (flight.path to result) }
        }
    }
}
