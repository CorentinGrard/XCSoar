// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.Format
import kotlin.math.abs

/** What the flight setup screen shows; ballast and bugs as in FlightState. */
data class FlightSetup(
    val ballast: Double,
    val maxBallast: Double,
    val bugs: Double,
    val wingLoading: Double?,
)

/**
 * Ballast and bugs, like XCSoar's flight setup dialog.  A value the
 * pilot just set is shown until the core's snapshot has it, so quick
 * taps add up.
 */
class FlightSetupViewModel(private val core: XcsoarCore) : ViewModel() {
    private val pendingBallast = MutableStateFlow<Double?>(null)
    private val pendingBugs = MutableStateFlow<Double?>(null)

    /** `null` until the core has started. */
    val setup: StateFlow<FlightSetup?> =
        combine(core.flightState, pendingBallast, pendingBugs) { state, ballast, bugs ->
            state ?: return@combine null
            if (ballast != null && same(ballast, state.ballast))
                pendingBallast.value = null
            if (bugs != null && same(bugs, state.bugs))
                pendingBugs.value = null
            FlightSetup(ballast ?: state.ballast, state.maxBallast,
                        bugs ?: state.bugs, state.wingLoading)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Ballast ± [BALLAST_STEP] litres, clamped to the plane's maximum. */
    fun changeBallast(delta: Double) {
        val s = setup.value ?: return
        setBallast(s.ballast + delta)
    }

    fun setBallast(litres: Double) {
        val s = setup.value ?: return
        val value = litres.coerceIn(0.0, s.maxBallast)
        pendingBallast.value = value
        viewModelScope.launch {
            try {
                core.setBallast(value)
            } catch (_: Exception) {
                pendingBallast.value = null
            }
        }
    }

    /** Bugs ± [delta] percent of performance lost, 0..50 like XCSoar. */
    fun changeBugs(delta: Int) {
        val s = setup.value ?: return
        val percent = (Format.bugsPercent(s.bugs) + delta).coerceIn(0, MAX_BUGS_PERCENT)
        val value = 1 - percent / 100.0
        pendingBugs.value = value
        viewModelScope.launch {
            try {
                core.setBugs(value)
            } catch (_: Exception) {
                pendingBugs.value = null
            }
        }
    }

    private fun same(a: Double, b: Double) = abs(a - b) < 1e-6

    companion object {
        const val BALLAST_STEP = 5.0
        const val BUGS_STEP = 5
        const val MAX_BUGS_PERCENT = 50
    }
}
