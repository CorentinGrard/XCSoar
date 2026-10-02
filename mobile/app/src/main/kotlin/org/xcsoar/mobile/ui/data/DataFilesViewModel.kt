// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.DataStatus
import org.xcsoar.mobile.core.XcsoarCore

data class DataFilesState(
    /** null until the core has answered. */
    val status: DataStatus? = null,
    /** The kind being copied and loaded, if any. */
    val busy: DataFile? = null,
    val error: String? = null,
)

/**
 * The map, airspace and waypoint files the core uses.
 *
 * @param importFile copies a picked document (by URI) into XCSoarData
 * and returns the path of the copy; Android code, kept out of here
 */
class DataFilesViewModel(
    private val core: XcsoarCore,
    private val importFile: suspend (uri: String, kind: DataFile) -> String,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(DataFilesState())
    val state: StateFlow<DataFilesState> = stateFlow.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = run(null) { }

    /** Copy the picked file into XCSoarData and load it. */
    fun choose(kind: DataFile, uri: String) = run(kind) {
        core.setDataFile(kind, importFile(uri, kind))
    }

    /** Stop using the file; the copy stays in XCSoarData. */
    fun remove(kind: DataFile) = run(kind) {
        core.setDataFile(kind, null)
    }

    private fun run(kind: DataFile?, action: suspend () -> Unit) {
        stateFlow.update { it.copy(busy = kind, error = null) }
        viewModelScope.launch {
            val error = try {
                action()
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            val status = try {
                core.dataStatus()
            } catch (e: Exception) {
                null
            }
            stateFlow.update { DataFilesState(status ?: it.status, busy = null, error = error) }
        }
    }
}
