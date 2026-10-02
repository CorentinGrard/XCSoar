// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.units

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.Format

/**
 * XCSoar's units settings.  The choice lives in the core (profile,
 * map labels); [Format.units] follows it, which updates every screen.
 */
class UnitsViewModel(private val core: XcsoarCore) : ViewModel() {
    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            core.units()?.let { Format.units = it }
        } catch (_: Exception) {
            // keep what is shown
        }
    }

    fun set(group: UnitGroup, unit: Int) {
        viewModelScope.launch {
            try {
                core.setUnit(group, unit)
            } catch (_: Exception) {
            }
            reload()
        }
    }

    fun preset(index: Int) {
        viewModelScope.launch {
            try {
                core.applyUnitPreset(index)
            } catch (_: Exception) {
            }
            reload()
        }
    }
}
