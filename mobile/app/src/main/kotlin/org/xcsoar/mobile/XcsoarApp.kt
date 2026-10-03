// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.xcsoar.AppPermissionManager
import org.xcsoar.CoreGraphics
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.REPOSITORY_URI
import org.xcsoar.mobile.core.RepositoryFile
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.ui.flights.FlightLog
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
            CoreGraphics.initialise(this)
            NativeCore.nativeInit(this, permissionManager)
            NativeXcsoarCore(xcsoarDataDir.path)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "no native core, using the fake one", e)
            null
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The core every screen shares: the native one, else the fake one. */
    val anyCore: XcsoarCore by lazy { core ?: FakeXcsoarCore(appScope) }

    override fun onCreate() {
        super.onCreate()
        WeatherUpdates(this, appScope)
    }

    /** The pilot chose the plane and crew since the app started. */
    var crewChosen = false

    /**
     * Copy a file the user picked into XCSoarData, into the folder
     * XCSoar uses for its kind (GetFileTypeDefaultDir() in
     * src/Repository/FileType.cpp), keeping its name.
     *
     * @return the path of the copy
     */
    suspend fun importDataFile(uri: Uri, kind: DataFile): String = withContext(Dispatchers.IO) {
        val folder = when (kind) {
            DataFile.MAP -> "maps"
            DataFile.AIRSPACE -> "airspace"
            DataFile.WAYPOINTS -> "waypoints"
            DataFile.RASP -> "weather/rasp"
        }
        val target = File(File(xcsoarDataDir, folder).apply { mkdirs() }, displayName(uri))
        val input = checkNotNull(contentResolver.openInputStream(uri)) { "cannot open $uri" }
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        target.path
    }

    val downloader by lazy { Downloader(cacheDir) }

    /**
     * Download a repository file into the XCSoarData folder XCSoar uses
     * for its kind.
     *
     * @return the path of the file
     */
    suspend fun downloadDataFile(file: RepositoryFile, progress: (Float) -> Unit): String {
        val folder = file.folder ?: error("${file.name}: unknown kind of file")
        val target = File(File(xcsoarDataDir, folder), File(file.name).name)
        downloader.download(file.uri, target, file.sha256, progress)
        return target.path
    }

    /**
     * Download [name] from the repository again and use it as [kind]:
     * RASP forecasts change every day under the same name.
     */
    suspend fun updateDataFile(kind: DataFile, name: String) {
        val files = anyCore.repositoryFiles(downloader.index(REPOSITORY_URI).path)
            ?: error("downloads need the native core")
        val file = files.firstOrNull { it.name == name }
            ?: error("$name is no longer in XCSoar's repository")
        anyCore.setDataFile(kind, downloadDataFile(file) {})
    }

    /** The file name of a picked document, safe to use as a file name. */
    private fun displayName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                                         null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "imported"
        val base = name.substringAfterLast('/').replace('\\', '_')
        return if (base.isBlank() || base == "." || base == "..") "imported" else base
    }

    /** The IGC files XCSoar's logger wrote (not the demo flight). */
    suspend fun flightLogs(): List<FlightLog> = withContext(Dispatchers.IO) {
        File(xcsoarDataDir, "logs").listFiles { f ->
            f.isFile && f.name.endsWith(".igc", ignoreCase = true) && f.name != DEMO_FLIGHT
        }.orEmpty().map { FlightLog(it.path, it.name, it.lastModified(), it.length()) }
    }

    /** The bundled demo flight, copied to the data directory. */
    fun demoFlight(): File {
        val file = File(xcsoarDataDir, "logs/$DEMO_FLIGHT")
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
        const val DEMO_FLIGHT = "demo.igc"
    }
}
