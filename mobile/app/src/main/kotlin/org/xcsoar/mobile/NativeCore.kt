// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.content.Context
import java.nio.ByteBuffer

/**
 * The JNI functions of libxcsoar_core.so (core/jni/CoreJni.cpp), one to
 * one on top of core/api/xcsoar_core.h.  Use [NativeXcsoarCore] instead.
 *
 * Status values are xcs_status: 0 = OK, negative = error.
 */
internal object NativeCore {
    init {
        System.loadLibrary("xcsoar_core")
    }

    /** Receives the callbacks; they arrive on the core main thread. */
    interface Listener {
        /** The buffer is only valid during the call. */
        fun onSnapshot(buffer: ByteBuffer)
        fun onEvent(type: Int, code: Int, text: String?, detail: String?)
    }

    @Volatile
    var listener: Listener? = null

    /** Once per process, before [nativeCreate]. */
    @JvmStatic external fun nativeInit(context: Context, permissionManager: Any)

    /** @return a handle, or 0 on error */
    @JvmStatic external fun nativeCreate(dataPath: String): Long
    @JvmStatic external fun nativeStart(core: Long): Int
    @JvmStatic external fun nativeStop(core: Long): Int
    @JvmStatic external fun nativeDestroy(core: Long)
    @JvmStatic external fun nativeSetMacCready(core: Long, macCready: Double): Int
    @JvmStatic external fun nativeSetBallast(core: Long, litres: Double): Int
    @JvmStatic external fun nativeSetBugs(core: Long, bugs: Double): Int
    @JvmStatic external fun nativeReplayStart(core: Long, path: String, timeScale: Double): Int
    @JvmStatic external fun nativeReplayStop(core: Long): Int
    /** @param kind an `xcs_data_file`; [path] null removes the file */
    @JvmStatic external fun nativeSetDataFile(core: Long, kind: Int, path: String?): Int
    /** @return the JSON of `xcs_get_data_status`, or null on error */
    @JvmStatic external fun nativeGetDataStatus(core: Long): String?
    /** @param surface an android.view.Surface */
    @JvmStatic external fun nativeMapAttach(core: Long, surface: Any, width: Int, height: Int, dpi: Int): Int
    @JvmStatic external fun nativeMapDetach(core: Long): Int
    @JvmStatic external fun nativeMapSetAircraftPosition(core: Long, x: Int, y: Int): Int
    @JvmStatic external fun nativeMapZoom(core: Long, steps: Int): Int
    @JvmStatic external fun nativeMapPan(core: Long, dx: Float, dy: Float): Int
    @JvmStatic external fun nativeMapScale(core: Long, factor: Float): Int
    @JvmStatic external fun nativeMapFollow(core: Long): Int
    @JvmStatic external fun nativeMapSetOrientation(core: Long, orientation: Int): Int
    /** @return an xcs_map_orientation, or -1 on error */
    @JvmStatic external fun nativeMapGetOrientation(core: Long): Int
    /** @return the JSON of `xcs_repository_list`, or null on error */
    @JvmStatic external fun nativeRepositoryList(path: String): String?
    /** @return the JSON of `xcs_get_airspace_warnings`, or null on error */
    @JvmStatic external fun nativeGetAirspaceWarnings(core: Long): String?
    /** @param mode XCS_ACK_WARNING (0) or XCS_ACK_DAY (1) */
    @JvmStatic external fun nativeAirspaceAcknowledge(core: Long, id: String, mode: Int): Int
    /** @return the JSON of `xcs_waypoints_search`, or null on error */
    @JvmStatic external fun nativeWaypointsSearch(core: Long, name: String, filter: Int, max: Int): String?
    @JvmStatic external fun nativeGotoWaypoint(core: Long, id: Int): Int
    @JvmStatic external fun nativeMapSetOption(core: Long, option: Int, value: Int): Int
    /** @return the value, or Int.MIN_VALUE on error */
    @JvmStatic external fun nativeMapGetOption(core: Long, option: Int): Int
    @JvmStatic external fun nativeSoundSetOption(core: Long, option: Int, value: Int): Int
    /** @return the value, or Int.MIN_VALUE on error */
    @JvmStatic external fun nativeSoundGetOption(core: Long, option: Int): Int
    /** @return the JSON of `xcs_map_items_at`, or null on error */
    @JvmStatic external fun nativeMapItemsAt(core: Long, x: Int, y: Int): String?

    /* called from native code */

    @JvmStatic
    fun onSnapshot(buffer: ByteBuffer) {
        listener?.onSnapshot(buffer)
    }

    @JvmStatic
    fun onEvent(type: Int, code: Int, text: String?, detail: String?) {
        listener?.onEvent(type, code, text, detail)
    }
}
