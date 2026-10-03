// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.Crew
import org.xcsoar.mobile.core.PlaneEdit
import org.xcsoar.mobile.core.PlaneList
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.core.TileLayouts
import org.xcsoar.mobile.core.TileType
import org.xcsoar.mobile.core.TileValue
import org.xcsoar.mobile.core.WeGlideAircraft
import org.xcsoar.mobile.core.WeGlideException
import org.xcsoar.mobile.core.WeGlideFlight
import org.xcsoar.mobile.core.NotamList
import org.xcsoar.mobile.core.NotamSettings
import org.xcsoar.mobile.core.RaspInfo
import org.xcsoar.mobile.core.TrackingSettings
import org.xcsoar.mobile.core.WeGlideSettings
import org.xcsoar.mobile.core.WeatherStation
import org.xcsoar.mobile.core.CoreEvent
import org.xcsoar.mobile.core.CoreEventType
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.DataStatus
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.AirspaceClassInfo
import org.xcsoar.mobile.core.AirspaceOption
import org.xcsoar.mobile.core.MapOption
import org.xcsoar.mobile.core.PlaneDetails
import org.xcsoar.mobile.core.PolarInfo
import org.xcsoar.mobile.core.SafetyOption
import org.xcsoar.mobile.core.RepositoryFile
import org.xcsoar.mobile.core.WaypointFilter
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.SnapshotDecoder
import org.xcsoar.mobile.core.SoundOption
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.core.UnitSettings
import org.xcsoar.mobile.core.TaskFileInfo
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskOp
import org.xcsoar.mobile.core.XcsoarCore
import java.nio.ByteBuffer

/**
 * [XcsoarCore] backed by XCSoar's glide computer in libxcsoar_core.so.
 *
 * Commands block until the core main thread has run them, so they run
 * on [Dispatchers.IO].  Only one instance may exist per process.
 *
 * @param dataPath the XCSoarData directory
 */
class NativeXcsoarCore(private val dataPath: String) : XcsoarCore, NativeCore.Listener {
    private val state = MutableStateFlow<FlightState?>(null)
    override val flightState: StateFlow<FlightState?> = state.asStateFlow()

    private val eventFlow = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CoreEvent> = eventFlow.asSharedFlow()

    private val lock = Mutex()
    /**
     * Held by network calls instead of [lock], so commands do not wait
     * for a download; [stop] takes both (always [lock] first).
     */
    private val networkLock = Mutex()
    @Volatile
    private var handle = 0L

    override suspend fun start() = lock.withLock {
        if (handle != 0L) return@withLock
        withContext(Dispatchers.IO) {
            NativeCore.listener = this@NativeXcsoarCore
            val h = NativeCore.nativeCreate(dataPath)
            check(h != 0L) { "xcs_create failed" }
            val status = NativeCore.nativeStart(h)
            if (status != 0) {
                NativeCore.nativeDestroy(h)
                error("xcs_start failed: $status")
            }
            handle = h
        }
    }

    override suspend fun stop() = lock.withLock {
        // waits for a download in progress
        networkLock.withLock {
            if (handle == 0L) return@withLock
            withContext(Dispatchers.IO) {
                NativeCore.nativeDestroy(handle)   // stops first
                handle = 0L
                NativeCore.listener = null
            }
        }
    }

    override suspend fun setMacCready(macCready: Double) =
        command { NativeCore.nativeSetMacCready(it, macCready) }

    override suspend fun setBallast(litres: Double) =
        command { NativeCore.nativeSetBallast(it, litres) }

    override suspend fun setBugs(bugs: Double) =
        command { NativeCore.nativeSetBugs(it, bugs) }

    override suspend fun setQnh(hpa: Double) =
        command { NativeCore.nativeSetQnh(it, hpa) }

    override suspend fun startReplay(path: String, timeScale: Double) =
        command { NativeCore.nativeReplayStart(it, path, timeScale) }

    override suspend fun stopReplay() =
        command { NativeCore.nativeReplayStop(it) }

    override suspend fun setDataFile(kind: DataFile, path: String?) =
        command { NativeCore.nativeSetDataFile(it, kind.code, path) }

    override suspend fun dataStatus(): DataStatus = lock.withLock {
        check(handle != 0L) { "core not started" }
        val json = withContext(Dispatchers.IO) { NativeCore.nativeGetDataStatus(handle) }
        DataStatus.parse(checkNotNull(json) { "xcs_get_data_status failed" })
    }

    override suspend fun repositoryFiles(indexPath: String): List<RepositoryFile> {
        val json = withContext(Dispatchers.IO) { NativeCore.nativeRepositoryList(indexPath) }
        return RepositoryFile.parseList(checkNotNull(json) { "cannot read the repository index" })
    }

    override suspend fun airspaceWarnings(): List<AirspaceWarningInfo> = lock.withLock {
        if (handle == 0L) return@withLock emptyList()
        val json = withContext(Dispatchers.IO) { NativeCore.nativeGetAirspaceWarnings(handle) }
        AirspaceWarningInfo.parseList(checkNotNull(json) { "xcs_get_airspace_warnings failed" })
    }

    override suspend fun acknowledgeAirspace(id: String, day: Boolean) =
        command { NativeCore.nativeAirspaceAcknowledge(it, id, if (day) 1 else 0) }

    override suspend fun searchWaypoints(name: String, filter: WaypointFilter,
                                         max: Int): List<WaypointInfo> = lock.withLock {
        check(handle != 0L) { "core not started" }
        val json = withContext(Dispatchers.IO) {
            NativeCore.nativeWaypointsSearch(handle, name, filter.code, max)
        }
        WaypointInfo.parseList(checkNotNull(json) { "xcs_waypoints_search failed" })
    }

    override suspend fun gotoWaypoint(id: Int) =
        command { NativeCore.nativeGotoWaypoint(it, id) }

    override val hasMap get() = true

    override suspend fun attachMap(surface: Any, width: Int, height: Int, dpi: Int) =
        command { NativeCore.nativeMapAttach(it, surface, width, height, dpi) }

    /* on the caller's thread: SurfaceHolder.Callback.surfaceDestroyed must
       not return before the core stopped drawing */
    override fun detachMap() {
        val h = handle
        if (h != 0L) NativeCore.nativeMapDetach(h)
    }

    override suspend fun setMapAircraftPosition(x: Int, y: Int) =
        command { NativeCore.nativeMapSetAircraftPosition(it, x, y) }

    override suspend fun zoomMap(steps: Int) =
        command { NativeCore.nativeMapZoom(it, steps) }

    override suspend fun panMap(dx: Float, dy: Float) =
        command { NativeCore.nativeMapPan(it, dx, dy) }

    override suspend fun scaleMap(factor: Float) =
        command { NativeCore.nativeMapScale(it, factor) }

    override suspend fun followMap() =
        command { NativeCore.nativeMapFollow(it) }

    override suspend fun mapOrientation(): MapOrientation? = lock.withLock {
        if (handle == 0L) return@withLock null
        MapOrientation.fromCode(withContext(Dispatchers.IO) {
            NativeCore.nativeMapGetOrientation(handle)
        })
    }

    override suspend fun mapOption(option: MapOption): Int? = lock.withLock {
        if (handle == 0L) return@withLock null
        withContext(Dispatchers.IO) { NativeCore.nativeMapGetOption(handle, option.code) }
            .takeIf { it != Int.MIN_VALUE }
    }

    override suspend fun setMapOption(option: MapOption, value: Int) =
        command { NativeCore.nativeMapSetOption(it, option.code, value) }

    override suspend fun airspaceOption(option: AirspaceOption): Int? = lock.withLock {
        if (handle == 0L) return@withLock null
        withContext(Dispatchers.IO) { NativeCore.nativeAirspaceGetOption(handle, option.code) }
            .takeIf { it != Int.MIN_VALUE }
    }

    override suspend fun setAirspaceOption(option: AirspaceOption, value: Int) =
        command { NativeCore.nativeAirspaceSetOption(it, option.code, value) }

    override suspend fun safetyOption(option: SafetyOption): Double? = lock.withLock {
        if (handle == 0L) return@withLock null
        withContext(Dispatchers.IO) { NativeCore.nativeSafetyGetOption(handle, option.code) }
            .takeIf { !it.isNaN() }
    }

    override suspend fun setSafetyOption(option: SafetyOption, value: Double) =
        command { NativeCore.nativeSafetySetOption(it, option.code, value) }

    override suspend fun airspaceClasses(): List<AirspaceClassInfo> = lock.withLock {
        if (handle == 0L) return@withLock emptyList()
        val json = withContext(Dispatchers.IO) { NativeCore.nativeAirspaceClasses(handle) }
        AirspaceClassInfo.parseList(checkNotNull(json) { "xcs_airspace_classes failed" })
    }

    override suspend fun setAirspaceClass(code: Int, display: Boolean, warning: Boolean) =
        command {
            NativeCore.nativeAirspaceSetClass(it, code, if (display) 1 else 0,
                                              if (warning) 1 else 0)
        }

    override suspend fun task(edited: Boolean): TaskInfo? = lock.withLock {
        if (handle == 0L) return@withLock null
        withContext(Dispatchers.IO) { NativeCore.nativeTaskGet(handle, if (edited) 1 else 0) }
            ?.let(TaskInfo::parse)
    }

    override suspend fun analysis(): Analysis? = lock.withLock {
        if (handle == 0L) return@withLock null
        withContext(Dispatchers.IO) { NativeCore.nativeGetAnalysis(handle) }
            ?.let(Analysis::parse)
    }

    /** A JSON getter; null if the core has not started or it failed. */
    private suspend fun <T> query(get: (Long) -> String?, parse: (String) -> T): T? =
        lock.withLock {
            if (handle == 0L) return@withLock null
            withContext(Dispatchers.IO) { get(handle) }?.let(parse)
        }

    /** A blocking network call: parse throws WeGlideException for an error. */
    private suspend fun <T> network(call: (Long) -> String?, parse: (String) -> T): T {
        val answer = networkCall(call)
        return parse(answer ?: throw WeGlideException("No answer"))
    }

    /** One network call at a time, beside the commands ([networkLock]). */
    private suspend fun <T> networkCall(call: (Long) -> T): T = networkLock.withLock {
        val h = handle
        check(h != 0L) { "core not started" }
        withContext(Dispatchers.IO) { call(h) }
    }

    override suspend fun tileTypes() =
        query(NativeCore::nativeTilesTypes, TileType::parseList).orEmpty()

    override suspend fun tileLayouts() =
        query(NativeCore::nativeTilesLayouts, TileLayouts::parse)

    override suspend fun setTile(layout: TileLayout, tile: Int, type: Int) =
        command { NativeCore.nativeTilesSet(it, layout.code, tile, type) }

    override suspend fun tiles(layout: TileLayout) =
        query({ NativeCore.nativeTilesUpdate(it, layout.code) }, TileValue::parseList)

    override suspend fun planes() = query(NativeCore::nativePlanesList, PlaneList::parse)

    override suspend fun polars() =
        query(NativeCore::nativePolarsList, PlaneList::parsePolars).orEmpty()

    override suspend fun polar(index: Int): PolarInfo? = lock.withLock {
        if (handle == 0L) return@withLock null
        withContext(Dispatchers.IO) { NativeCore.nativePolarGet(handle, index) }
            ?.let(PolarInfo::parse)
    }

    override suspend fun setPlaneDetails(path: String, details: PlaneDetails) = command {
        NativeCore.nativePlaneSetDetails(it, path, details.emptyMass, details.referenceMass,
                                         details.maxBallast, details.dumpTime,
                                         details.maxSpeed, details.wingArea, details.handicap)
    }

    override suspend fun crewMass(): Double? = lock.withLock {
        if (handle == 0L) return@withLock null
        withContext(Dispatchers.IO) { NativeCore.nativeGetCrewMass(handle) }
            .takeIf { !it.isNaN() }
    }

    override suspend fun setCrewMass(kg: Double) =
        command { NativeCore.nativeSetCrewMass(it, kg) }

    override suspend fun savePlane(plane: PlaneEdit): String = lock.withLock {
        check(handle != 0L) { "core not started" }
        withContext(Dispatchers.IO) {
            NativeCore.nativePlaneSave(handle, plane.path, plane.registration,
                                       plane.competitionId, plane.type, plane.polar,
                                       plane.weGlideType, plane.doubleSeater)
        } ?: error("xcs_plane_save failed")
    }

    override suspend fun activatePlane(path: String) =
        command { NativeCore.nativePlaneActivate(it, path) }

    override suspend fun deletePlane(path: String) =
        command { NativeCore.nativePlaneDelete(it, path) }

    override suspend fun crew() = query(NativeCore::nativeCrewGet, Crew::parse)

    override suspend fun setCrew(pilot: String?, copilot: String?) =
        command { NativeCore.nativeCrewSet(it, pilot, copilot) }

    override suspend fun notamSettings() =
        query(NativeCore::nativeNotamSettingsGet, NotamSettings::parse)

    override suspend fun setNotamSettings(settings: NotamSettings) =
        command { NativeCore.nativeNotamSettingsSet(it, settings.toJson()) }

    override suspend fun notams() =
        query(NativeCore::nativeNotamList, NotamList::parse) ?: NotamList()

    override suspend fun refreshNotams(): Boolean = lock.withLock {
        check(handle != 0L) { "core not started" }
        withContext(Dispatchers.IO) { NativeCore.nativeNotamRefresh(handle) } == 0
    }

    override suspend fun raspInfo() =
        query(NativeCore::nativeRaspGet, RaspInfo::parse) ?: RaspInfo()

    override suspend fun setRasp(field: Int, time: String?) =
        command { NativeCore.nativeRaspSet(it, field, time) }

    override suspend fun weatherStations() =
        query(NativeCore::nativeWeatherList, WeatherStation::parseList).orEmpty()

    override suspend fun addWeatherStation(code: String): Boolean = lock.withLock {
        check(handle != 0L) { "core not started" }
        withContext(Dispatchers.IO) { NativeCore.nativeWeatherAdd(handle, code) } == 0
    }

    override suspend fun removeWeatherStation(code: String) =
        command { NativeCore.nativeWeatherRemove(it, code) }

    override suspend fun updateWeather() {
        val status = networkCall(NativeCore::nativeWeatherUpdate)
        check(status == 0) { "No weather received" }
    }

    override suspend fun trackingSettings() =
        query(NativeCore::nativeTrackingGet, TrackingSettings::parse)

    override suspend fun setTrackingSettings(settings: TrackingSettings) =
        command { NativeCore.nativeTrackingSet(it, settings.toJson()) }

    override suspend fun weGlideSettings() =
        query(NativeCore::nativeWeGlideGet, WeGlideSettings::parse)

    override suspend fun setWeGlideSettings(settings: WeGlideSettings) =
        command {
            NativeCore.nativeWeGlideSet(it, settings.enabled, settings.pilotId,
                                        settings.birthdate)
        }

    override suspend fun searchWeGlideAircraft(query: String, max: Int) =
        query({ NativeCore.nativeWeGlideAircraftSearch(it, query, max) },
              WeGlideAircraft::parseList).orEmpty()

    override suspend fun updateWeGlideAircraftList() =
        network(NativeCore::nativeWeGlideAircraftUpdate) { WeGlideException.check(it); Unit }

    override suspend fun weGlideAircraft(id: Int) =
        network({ NativeCore.nativeWeGlideAircraftGet(it, id) }, WeGlideAircraft::parse)

    override suspend fun uploadToWeGlide(igcPath: String) =
        network({ NativeCore.nativeWeGlideUpload(it, igcPath) }, WeGlideFlight::parse)

    override suspend fun editTask(op: TaskOp, index: Int, value: Double) =
        command { NativeCore.nativeTaskEdit(it, op.code, index, value) }

    override suspend fun taskFiles(): List<TaskFileInfo> = lock.withLock {
        check(handle != 0L) { "core not started" }
        val json = withContext(Dispatchers.IO) { NativeCore.nativeTaskListFiles(handle) }
        TaskFileInfo.parseList(checkNotNull(json) { "xcs_task_list_files failed" })
    }

    override suspend fun loadTask(file: TaskFileInfo) =
        command { NativeCore.nativeTaskLoad(it, file.path, file.index) }

    override suspend fun saveTask(name: String) =
        command { NativeCore.nativeTaskSave(it, name) }

    override suspend fun units(): UnitSettings? = lock.withLock {
        if (handle == 0L) return@withLock null
        withContext(Dispatchers.IO) { NativeCore.nativeUnitsGet(handle) }
            ?.let(UnitSettings::parse)
    }

    override suspend fun setUnit(group: UnitGroup, unit: Int) =
        command { NativeCore.nativeUnitsSet(it, group.code, unit) }

    override suspend fun applyUnitPreset(index: Int) =
        command { NativeCore.nativeUnitsPreset(it, index) }

    override suspend fun soundOption(option: SoundOption): Int? = lock.withLock {
        if (handle == 0L) return@withLock null
        withContext(Dispatchers.IO) { NativeCore.nativeSoundGetOption(handle, option.code) }
            .takeIf { it != Int.MIN_VALUE }
    }

    override suspend fun setSoundOption(option: SoundOption, value: Int) =
        command { NativeCore.nativeSoundSetOption(it, option.code, value) }

    override suspend fun mapItemsAt(x: Int, y: Int): List<MapItemInfo> = lock.withLock {
        check(handle != 0L) { "core not started" }
        val json = withContext(Dispatchers.IO) { NativeCore.nativeMapItemsAt(handle, x, y) }
        MapItemInfo.parseList(checkNotNull(json) { "xcs_map_items_at failed" })
    }

    override suspend fun setMapOrientation(orientation: MapOrientation) =
        command { NativeCore.nativeMapSetOrientation(it, orientation.code) }

    private suspend fun command(block: (Long) -> Int) = lock.withLock {
        check(handle != 0L) { "core not started" }
        val status = withContext(Dispatchers.IO) { block(handle) }
        check(status == 0) { "command failed: $status" }
    }

    /* NativeCore.Listener, called on the core main thread */

    override fun onSnapshot(buffer: ByteBuffer) {
        state.value = SnapshotDecoder.decode(buffer)
    }

    override fun onEvent(type: Int, code: Int, text: String?, detail: String?) {
        val event = when (type) {
            CoreEventType.GLIDE_COMPUTER -> CoreEvent.GlideComputer(GlideComputerEvent.fromCode(code))
            CoreEventType.MESSAGE -> CoreEvent.Message(text ?: "", detail)
            CoreEventType.REPLAY_FINISHED -> CoreEvent.ReplayFinished
            else -> return
        }
        eventFlow.tryEmit(event)
    }
}
