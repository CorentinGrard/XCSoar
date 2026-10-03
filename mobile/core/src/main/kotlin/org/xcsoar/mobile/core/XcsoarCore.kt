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

    /** Water ballast, litres (0..[FlightState.maxBallast]). */
    suspend fun setBallast(litres: Double)

    /** Bugs, 0.5..1 (1 = clean), as in [FlightState.bugs]. */
    suspend fun setBugs(bugs: Double)

    /** QNH, hPa ([FlightState.MIN_QNH]..[FlightState.MAX_QNH]). */
    suspend fun setQnh(hpa: Double)

    /**
     * Use [path] for [kind] (null removes it), save the profile and load
     * the data again.  Suspends while the core loads.
     */
    suspend fun setDataFile(kind: DataFile, path: String?)

    /** The configured data files and what was loaded from them. */
    suspend fun dataStatus(): DataStatus

    /** Parse a downloaded repository index ([REPOSITORY_URI]); null if unsupported. */
    suspend fun repositoryFiles(indexPath: String): List<RepositoryFile>? = null

    /** Waypoints whose name contains [name], nearest first. */
    suspend fun searchWaypoints(name: String, filter: WaypointFilter, max: Int): List<WaypointInfo> =
        emptyList()

    /**
     * Fly directly to this waypoint (XCSoar's "Go to"); it becomes the
     * next point.  Fails if the profile only allows landable targets.
     */
    suspend fun gotoWaypoint(id: Int) {}

    /** Active airspace warnings, most severe first. */
    suspend fun airspaceWarnings(): List<AirspaceWarningInfo> = emptyList()

    /** Acknowledge a warning until it changes, or for the whole [day]. */
    suspend fun acknowledgeAirspace(id: String, day: Boolean) {}

    /** An airspace warning option; null if the core has none. */
    suspend fun airspaceOption(option: AirspaceOption): Int? = null

    /** Set an airspace warning option; saved in the profile. */
    suspend fun setAirspaceOption(option: AirspaceOption, value: Int) {}

    /** A safety margin of the glide computer; null if the core has none. */
    suspend fun safetyOption(option: SafetyOption): Double? = null

    /** Set a safety margin; saved in the profile. */
    suspend fun setSafetyOption(option: SafetyOption, value: Double) {}

    /** XCSoar's airspace classes, drawn and warned of or not. */
    suspend fun airspaceClasses(): List<AirspaceClassInfo> = emptyList()

    /** Draw and warn of one airspace class; saved in the profile. */
    suspend fun setAirspaceClass(code: Int, display: Boolean, warning: Boolean) {}

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

    /**
     * The active task, or with [edited] the copy being edited (null if
     * none).
     */
    suspend fun task(edited: Boolean): TaskInfo? = null

    /** XCSoar's analysis pages; null if the core has not started. */
    suspend fun analysis(): Analysis? = null

    /** The InfoBox types a tile can show, by name. */
    suspend fun tileTypes(): List<TileType> = emptyList()

    /** The types in each layout's tiles; null if the core has not started. */
    suspend fun tileLayouts(): TileLayouts? = null

    /** Show InfoBox [type] in [tile] of [layout] (saved in the profile). */
    suspend fun setTile(layout: TileLayout, tile: Int, type: Int) {}

    /** The tiles of [layout] now; null if the core has not started. */
    suspend fun tiles(layout: TileLayout): List<TileValue>? = null

    /** The plane files; null if the core has not started. */
    suspend fun planes(): PlaneList? = null

    /** XCSoar's built-in polars, by index. */
    suspend fun polars(): List<String> = emptyList()

    /** Create or change a plane; @return its path. */
    suspend fun savePlane(plane: PlaneEdit): String = error("no planes")

    /** Fly this plane from now on. */
    suspend fun activatePlane(path: String) {}

    suspend fun deletePlane(path: String) {}

    /** The pilot and co-pilot for the IGC file; null if not started. */
    suspend fun crew(): Crew? = null

    /** Null keeps a name; an empty [copilot] means flying solo. */
    suspend fun setCrew(pilot: String?, copilot: String?) {}

    suspend fun weGlideSettings(): WeGlideSettings? = null

    suspend fun setWeGlideSettings(settings: WeGlideSettings) {}

    /** WeGlide aircraft types from the downloaded list. */
    suspend fun searchWeGlideAircraft(query: String, max: Int = 50): List<WeGlideAircraft> =
        emptyList()

    /** Download WeGlide's aircraft list.  @throws WeGlideException */
    suspend fun updateWeGlideAircraftList() {}

    /** One type, with its number of seats.  @throws WeGlideException */
    suspend fun weGlideAircraft(id: Int): WeGlideAircraft = error("no WeGlide")

    /** Upload an IGC file.  @throws WeGlideException */
    suspend fun uploadToWeGlide(igcPath: String): WeGlideFlight = error("no WeGlide")

    /**
     * Change the task (`xcs_task_edit`); most operations need
     * [TaskOp.BEGIN] first.
     *
     * @throws IllegalStateException if not allowed, e.g. a commit of
     * an invalid task
     */
    suspend fun editTask(op: TaskOp, index: Int = 0, value: Double = 0.0) {}

    /** The task files XCSoar finds. */
    suspend fun taskFiles(): List<TaskFileInfo> = emptyList()

    /** Load a task file into the editor (editing starts). */
    suspend fun loadTask(file: TaskFileInfo) {}

    /** Save the edited task as XCSoarData/tasks/<name>.tsk. */
    suspend fun saveTask(name: String) {}

    /** XCSoar's units and the pilot's choice; null until started. */
    suspend fun units(): UnitSettings? = null

    /** Unit of a group, one of its choices (saved in the profile). */
    suspend fun setUnit(group: UnitGroup, unit: Int) {}

    /** Load XCSoar's unit preset [index] (see [UnitSettings.presets]). */
    suspend fun applyUnitPreset(index: Int) {}

    /** A sound option (saved in the profile); null without audio output. */
    suspend fun soundOption(option: SoundOption): Int? = null

    suspend fun setSoundOption(option: SoundOption, value: Int) {}

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

/** Values of `xcs_sound_option` (core/api/xcsoar_core.h). */
enum class SoundOption(val code: Int) {
    /** 0/1: XCSoar's vario sound */
    VARIO(1),
    /** 0..100, under the system media volume */
    VARIO_VOLUME(2),
    /** 0 the vario, 1 auto: speed to fly in cruise (needs airspeed) */
    VARIO_SWITCHING(3),
    /** 0/1: silent while the lift is in the dead band */
    VARIO_DEAD_BAND(4),
    /** cm/s, -500..0: the dead band's lower edge */
    VARIO_DEAD_BAND_MIN(5),
    /** cm/s, 0..200: the dead band's upper edge */
    VARIO_DEAD_BAND_MAX(6),
}

/** Values of `xcs_airspace_option` (core/api/xcsoar_core.h). */
enum class AirspaceOption(val code: Int) {
    /** 0/1: XCSoar computes airspace warnings */
    WARNINGS(1),
    /** 0/1: a tone for a new or worse warning */
    ALERT_SOUND(2),
    /** 0/1: vibrate for a new or worse warning */
    ALERT_VIBRATION(3),
    /** 0..600: hide a warning after this many seconds, 0 never */
    AUTO_HIDE(4),
    /** 10..1000: warn this many seconds before entering */
    WARNING_TIME(5),
    /** 10..1000: an acknowledged warning stays quiet this many seconds */
    ACK_TIME(6),
}

/** Values of `xcs_safety_option` (core/api/xcsoar_core.h), SI units. */
enum class SafetyOption(val code: Int) {
    /** m, 0..2000: height above the field to arrive at */
    ARRIVAL_HEIGHT(1),
    /** m, 0..1000: terrain clearance on final glide */
    TERRAIN_HEIGHT(2),
    /** m/s, 0..10: MacCready for reach and arrival heights */
    MC(3),
    /** 0..1: lowers MacCready for speed to fly when low */
    RISK_FACTOR(4),
    /** 0 nearest, 1 along the task, 2 toward home */
    ALTERNATES(5),
    /** 0/1: the turn back marker on the map */
    TURN_BACK_MARKER(6),
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
