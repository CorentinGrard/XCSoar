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
import org.xcsoar.mobile.core.CoreEvent
import org.xcsoar.mobile.core.CoreEventType
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.DataStatus
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.core.MapOption
import org.xcsoar.mobile.core.RepositoryFile
import org.xcsoar.mobile.core.WaypointFilter
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.core.SnapshotDecoder
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
        if (handle == 0L) return@withLock
        withContext(Dispatchers.IO) {
            NativeCore.nativeDestroy(handle)   // stops first
            handle = 0L
            NativeCore.listener = null
        }
    }

    override suspend fun setMacCready(macCready: Double) =
        command { NativeCore.nativeSetMacCready(it, macCready) }

    override suspend fun setBallast(litres: Double) =
        command { NativeCore.nativeSetBallast(it, litres) }

    override suspend fun setBugs(bugs: Double) =
        command { NativeCore.nativeSetBugs(it, bugs) }

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
