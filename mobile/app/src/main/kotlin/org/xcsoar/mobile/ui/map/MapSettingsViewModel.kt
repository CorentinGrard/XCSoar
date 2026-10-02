// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.MapOption
import org.xcsoar.mobile.core.XcsoarCore

/** The map's display options, as the core (the profile) has them. */
class MapSettingsViewModel(private val core: XcsoarCore) : ViewModel() {
    private val optionsFlow = MutableStateFlow<Map<MapOption, Int>>(emptyMap())
    /** Missing entries: not known (yet). */
    val options: StateFlow<Map<MapOption, Int>> = optionsFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            optionsFlow.value = MapOption.entries.mapNotNull { option ->
                try {
                    core.mapOption(option)?.let { option to it }
                } catch (_: Exception) {
                    null
                }
            }.toMap()
        }
    }

    fun set(option: MapOption, value: Int) {
        optionsFlow.value = optionsFlow.value + (option to value)
        viewModelScope.launch {
            try {
                core.setMapOption(option, value)
            } catch (_: Exception) {
                refresh()
            }
        }
    }
}
