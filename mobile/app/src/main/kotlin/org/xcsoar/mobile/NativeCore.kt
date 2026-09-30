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
    @JvmStatic external fun nativeReplayStart(core: Long, path: String, timeScale: Double): Int
    @JvmStatic external fun nativeReplayStop(core: Long): Int

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
