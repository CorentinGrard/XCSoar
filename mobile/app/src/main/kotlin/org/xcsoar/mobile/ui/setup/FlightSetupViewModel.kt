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
import org.xcsoar.mobile.core.AutoMacCreadyMode
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.MacCreadyOption
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.Format
import kotlin.math.abs

/** What the flight setup screen shows; the values as in FlightState. */
data class FlightSetup(
    val ballast: Double,
    val maxBallast: Double,
    val bugs: Double,
    val wingLoading: Double?,
    /** kg; null until read from the core. */
    val crewMass: Double? = null,
    /** null while not set: the standard atmosphere. */
    val qnh: Double? = null,
    /** null without a barometer. */
    val staticPressure: Double? = null,
    val baroAltitude: Double? = null,
    /** m/s */
    val macCready: Double = 0.0,
    val autoMacCready: Boolean = false,
    /** null until read from the core. */
    val autoMode: AutoMacCreadyMode? = null,
)

/**
 * MacCready, ballast, bugs and QNH, like XCSoar's flight setup dialog
 * and its MacCready InfoBox panels.  A value the
 * pilot just set is shown until the core's snapshot has it, so quick
 * taps add up.
 */
class FlightSetupViewModel(private val core: XcsoarCore) : ViewModel() {
    private val pendingBallast = MutableStateFlow<Double?>(null)
    private val pendingBugs = MutableStateFlow<Double?>(null)
    private val pendingQnh = MutableStateFlow<Double?>(null)
    /** Not in the snapshot: read once, then what the pilot set. */
    private val crewMass = MutableStateFlow<Double?>(null)
    private val pendingMacCready = MutableStateFlow<Double?>(null)
    private val pendingAuto = MutableStateFlow<Boolean?>(null)
    /** Not in the snapshot: read once, then what the pilot set. */
    private val autoMode = MutableStateFlow<AutoMacCreadyMode?>(null)

    init {
        viewModelScope.launch {
            try {
                crewMass.value = core.crewMass()
                autoMode.value =
                    AutoMacCreadyMode.fromCode(core.macCreadyOption(MacCreadyOption.AUTO_MODE))
            } catch (_: Exception) {
            }
        }
    }

    private val withoutMacCready =
        combine(core.flightState, pendingBallast, pendingBugs, pendingQnh, crewMass) {
                state, ballast, bugs, qnh, crew ->
            state ?: return@combine null
            if (ballast != null && same(ballast, state.ballast))
                pendingBallast.value = null
            if (bugs != null && same(bugs, state.bugs))
                pendingBugs.value = null
            if (qnh != null && state.qnh?.let { same(qnh, it) } == true)
                pendingQnh.value = null
            FlightSetup(ballast ?: state.ballast, state.maxBallast,
                        bugs ?: state.bugs, state.wingLoading, crew,
                        qnh ?: state.qnh, state.staticPressure,
                        // computed with the old QNH until the core has the new one
                        state.baroAltitude.takeIf { qnh == null })
        }

    /** `null` until the core has started. */
    val setup: StateFlow<FlightSetup?> =
        combine(withoutMacCready, core.flightState, pendingMacCready, pendingAuto, autoMode) {
                setup, state, mc, auto, mode ->
            setup ?: return@combine null
            state ?: return@combine null
            if (mc != null && same(mc, state.macCready))
                pendingMacCready.value = null
            if (auto != null && auto == state.autoMacCready)
                pendingAuto.value = null
            setup.copy(macCready = mc ?: state.macCready,
                       autoMacCready = auto ?: state.autoMacCready, autoMode = mode)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** MacCready one step up or down; like the pilot's change in XCSoar, it
        turns the automatic MacCready off. */
    fun changeMacCready(direction: Int) {
        val s = setup.value ?: return
        val value = Format.stepVerticalSpeed(s.macCready, direction).coerceIn(0.0, MAX_MC)
        pendingMacCready.value = value
        pendingAuto.value = false
        viewModelScope.launch {
            try {
                core.setMacCready(value)
            } catch (_: Exception) {
                pendingMacCready.value = null
                pendingAuto.value = null
            }
        }
    }

    fun setAutoMacCready(on: Boolean) {
        pendingAuto.value = on
        viewModelScope.launch {
            try {
                core.setMacCreadyOption(MacCreadyOption.AUTO, if (on) 1 else 0)
            } catch (_: Exception) {
                pendingAuto.value = null
            }
        }
    }

    fun setAutoMode(mode: AutoMacCreadyMode) {
        val old = autoMode.value
        autoMode.value = mode
        viewModelScope.launch {
            try {
                core.setMacCreadyOption(MacCreadyOption.AUTO_MODE, mode.code)
            } catch (_: Exception) {
                autoMode.value = old
            }
        }
    }

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

    /** Crew mass one step up or down ([Format.stepMass]), 0..[MAX_CREW_MASS] kg. */
    fun changeCrewMass(direction: Int) {
        val old = crewMass.value ?: return
        val value = Format.stepMass(old, direction).coerceIn(0.0, MAX_CREW_MASS)
        crewMass.value = value
        viewModelScope.launch {
            try {
                core.setCrewMass(value)
            } catch (_: Exception) {
                crewMass.value = old
            }
        }
    }

    /** QNH one step up or down ([Format.stepPressure]), within XCSoar's range. */
    fun changeQnh(direction: Int) {
        val s = setup.value ?: return
        val value = Format.stepPressure(s.qnh ?: FlightState.STANDARD_QNH, direction)
            .coerceIn(FlightState.MIN_QNH, FlightState.MAX_QNH)
        pendingQnh.value = value
        viewModelScope.launch {
            try {
                core.setQnh(value)
            } catch (_: Exception) {
                pendingQnh.value = null
            }
        }
    }

    private fun same(a: Double, b: Double) = abs(a - b) < 1e-6

    companion object {
        const val BALLAST_STEP = 5.0
        const val BUGS_STEP = 5
        const val MAX_BUGS_PERCENT = 50
        /** xcs_set_crew_mass() */
        const val MAX_CREW_MASS = 300.0
        /** xcs_set_mac_cready() */
        const val MAX_MC = 5.0
    }
}
