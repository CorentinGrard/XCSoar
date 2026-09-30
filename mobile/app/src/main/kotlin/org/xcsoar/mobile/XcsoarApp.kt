// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.app.Application
import android.util.Log
import org.xcsoar.AppPermissionManager
import org.xcsoar.mobile.core.XcsoarCore
import java.io.File

/**
 * Owns what lives as long as the process: the core (one per process,
 * XCSoar has process-wide state) and the permission manager.
 */
class XcsoarApp : Application() {
    internal val permissionManager by lazy { AppPermissionManager(this) }

    /** The XCSoarData directory; visible over USB for copying data files. */
    val xcsoarDataDir: File by lazy {
        // exists before the core starts (its data layout migration writes
        // a marker there first)
        File(getExternalFilesDir(null) ?: filesDir, "XCSoarData").apply { mkdirs() }
    }

    /**
     * The native core, or `null` if libxcsoar_core.so is not in the APK
     * for this device's ABI (the UI then falls back to a fake core).
     */
    val core: XcsoarCore? by lazy {
        try {
            NativeCore.nativeInit(this, permissionManager)
            NativeXcsoarCore(xcsoarDataDir.path)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "no native core, using the fake one", e)
            null
        }
    }

    /** The bundled demo flight, copied to the data directory. */
    fun demoFlight(): File {
        val file = File(xcsoarDataDir, "logs/demo.igc")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            assets.open("01lz1hq1.igc").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return file
    }

    private companion object {
        const val TAG = "XcsoarApp"
    }
}
