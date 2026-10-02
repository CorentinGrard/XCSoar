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

    /**
     * Use [path] for [kind] (null removes it), save the profile and load
     * the data again.  Suspends while the core loads.
     */
    suspend fun setDataFile(kind: DataFile, path: String?)

    /** The configured data files and what was loaded from them. */
    suspend fun dataStatus(): DataStatus

    /** Parse a downloaded repository index ([REPOSITORY_URI]); null if unsupported. */
    suspend fun repositoryFiles(indexPath: String): List<RepositoryFile>? = null

    /**
     * Fly directly to this waypoint (XCSoar's "Go to"); it becomes the
     * next point.  Fails if the profile only allows landable targets.
     */
    suspend fun gotoWaypoint(id: Int) {}

    /** Whether this core draws XCSoar's moving map ([attachMap]). */
    val hasMap: Boolean get() = false

    /**
     * Draw the map into [surface] (an `android.view.Surface` on Android),
     * [width] × [height] pixels.  Call again when the size changes.
     */
    suspend fun attachMap(surface: Any, width: Int, height: Int, dpi: Int) {}

    /** Stop drawing; blocks until done, so the surface may be destroyed. */
    fun detachMap() {}

    /** Where the aircraft is drawn on the map, in pixels. */
    suspend fun setMapAircraftPosition(x: Int, y: Int) {}

    /** Zoom in (negative) or out (positive) along XCSoar's scale list. */
    suspend fun zoomMap(steps: Int) {}

    /** Move the map with the finger (pixels); it stops following the aircraft. */
    suspend fun panMap(dx: Float, dy: Float) {}

    /** Zoom continuously; [factor] > 1 zooms in. */
    suspend fun scaleMap(factor: Float) {}

    /** Centre on the aircraft again and follow it. */
    suspend fun followMap() {}

    /** The map orientation (saved in the profile); null without a map. */
    suspend fun mapOrientation(): MapOrientation? = null

    suspend fun setMapOrientation(orientation: MapOrientation) {}

    /** A display option of the map; null without a map. */
    suspend fun mapOption(option: MapOption): Int? = null

    /** Set a display option (saved in the profile). */
    suspend fun setMapOption(option: MapOption, value: Int) {}

    /** What is on the map around pixel ([x], [y]), nearest first. */
    suspend fun mapItemsAt(x: Int, y: Int): List<MapItemInfo> = emptyList()

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

/** Values of `xcs_map_orientation` (core/api/xcsoar_core.h). */
enum class MapOrientation(val code: Int) {
    TRACK_UP(0),
    NORTH_UP(1),
    /** Towards the next waypoint. */
    TARGET_UP(2),
    HEADING_UP(3),
    /** The wind comes from the top. */
    WIND_UP(4);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Values of `xcs_map_option` (core/api/xcsoar_core.h). */
enum class MapOption(val code: Int) {
    /** 0/1 */
    TERRAIN(1),
    /** XCSoar's ramp number, see [TerrainRamp]. */
    TERRAIN_RAMP(2),
    /** 0/1: roads, rivers, towns */
    TOPOGRAPHY(3),
    /** 0 off, 1 long, 2 short, 3 full */
    TRAIL(4),
}

/** XCSoar's terrain colour ramps (TerrainRenderer.cpp), the ones offered. */
enum class TerrainRamp(val code: Int, val label: String) {
    PASTEL(11, "Pastel"),
    LOW_LANDS(0, "Low lands"),
    MOUNTAINOUS(1, "Mountainous"),
    IMHOF_ATLAS(5, "Imhof Atlas"),
    ICAO(6, "ICAO"),
    FRENCH_SIA(14, "French SIA VFR chart"),
    GERMAN_DFS(13, "German DFS VFR chart"),
    SANDSTONE(10, "Sandstone"),
    GREY(7, "Grey"),
    HIGH_CONTRAST(15, "High contrast"),
}
