// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * XCSoar's glide computer, as the UI sees it.
 *
 * The real implementation binds `libxcsoar_core.so` through JNI
 * (core/api/xcsoar_core.h); [FakeXcsoarCore] plays scripted states for
 * previews and tests.  The UI and its view models only know this
 * interface.
 */
interface XcsoarCore {
    /** The latest state; `null` until the core has started. */
    val flightState: StateFlow<FlightState?>

    /** Things that happen (take-off, thermal entered, messages...). */
    val events: SharedFlow<CoreEvent>

    suspend fun start()
    suspend fun stop()

    /** MacCready setting, m/s (0..5). */
    suspend fun setMacCready(macCready: Double)

    /** Replay an IGC or NMEA file; [timeScale] 1 = real time. */
    suspend fun startReplay(path: String, timeScale: Double = 1.0)
    suspend fun stopReplay()
}

/** Values of `xcs_event_type` (core/api/xcsoar_core.h). */
object CoreEventType {
    const val GLIDE_COMPUTER = 1
    const val MESSAGE = 2
    const val REPLAY_FINISHED = 3
}

sealed interface CoreEvent {
    data class GlideComputer(val event: GlideComputerEvent) : CoreEvent
    data class Message(val text: String, val detail: String?) : CoreEvent
    data object ReplayFinished : CoreEvent
}

/** The stable codes of `xcs_gce` (core/api/xcsoar_core.h). */
enum class GlideComputerEvent(val code: Int) {
    OTHER(0),
    TAKEOFF(1),
    LANDING(2),
    FLIGHTMODE_CLIMB(3),
    FLIGHTMODE_CRUISE(4),
    FLIGHTMODE_FINALGLIDE(5),
    FINALGLIDE_ABOVE(6),
    FINALGLIDE_BELOW(7),
    FINALGLIDE_TERRAIN(8),
    LANDABLE_UNREACHABLE(9),
    TASK_START(10),
    TASK_NEXTWAYPOINT(11),
    TASK_FINISH(12),
    ARM_READY(13),
    AIRSPACE_NEAR(14),
    AIRSPACE_ENTER(15),
    AIRSPACE_LEAVE(16),
    FLARM_TRAFFIC(17),
    FLARM_NOTRAFFIC(18),
    FLARM_NEWTRAFFIC(19),
    GPS_CONNECTION_WAIT(20),
    GPS_FIX_WAIT(21),
    HEIGHT_MAX(22),
    TEAM_POS_REACHED(23),
    POLAR_CHANGED(24),
    ALTERNATE_CHANGED(25),
    COMMPORT_RESTART(26);

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** Unknown codes (from a newer core) map to [OTHER]. */
        fun fromCode(code: Int): GlideComputerEvent = byCode[code] ?: OTHER
    }
}
