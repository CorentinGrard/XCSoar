// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.OpenableColumns
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.xcsoar.AppPermissionManager
import org.xcsoar.CoreBattery
import org.xcsoar.CoreGraphics
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.core.GeoPosition
import org.xcsoar.mobile.core.REPOSITORY_URI
import org.xcsoar.mobile.core.RepositoryFile
import org.xcsoar.mobile.core.XcsoarCore
import org.xcsoar.mobile.core.WeGlideFlight
import org.xcsoar.mobile.ui.flights.FlightLog
import org.xcsoar.mobile.ui.flights.UploadStore
import org.xcsoar.mobile.ui.theme.ThemeChoice
import java.io.File
import java.util.Locale

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
            CoreBattery.register(this)
            NativeXcsoarCore(xcsoarDataDir.path, profile?.takeIf { File(it).isFile })
        } catch (e: LinkageError) {
            /* UnsatisfiedLinkError the first time; NoClassDefFoundError
               for every later app in the same process (JVM tests), as
               NativeCore's initialiser failed */
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

    private val uiPreferences by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    /** The theme the pilot chose; kept on the phone, not in the profile. */
    var theme: ThemeChoice
        get() = ThemeChoice.entries.firstOrNull {
            it.name == uiPreferences.getString("theme", null)
        } ?: ThemeChoice.SYSTEM
        set(value) = uiPreferences.edit().putString("theme", value.name).apply()

    /**
     * The profile file the core starts with; null for XCSoar's
     * "default.prf".  Kept on the phone: the profile cannot name itself.
     */
    var profile: String?
        get() = uiPreferences.getString("profile", null)
        set(value) = uiPreferences.edit().putString("profile", value).commit().let {}

    /**
     * Copy a profile the user picked (e.g. from XCSoar on another
     * device) into XCSoarData/profiles, keeping its name.
     *
     * @return the path of the copy
     * @throws IllegalStateException if a profile has that name already
     */
    suspend fun importProfile(uri: Uri): String = withContext(Dispatchers.IO) {
        var name = displayName(uri)
        if (!name.endsWith(".prf", ignoreCase = true)) name += ".prf"
        val target = File(File(xcsoarDataDir, "profiles").apply { mkdirs() }, name)
        check(!target.exists()) { "A profile named ${name.removeSuffix(".prf")} exists already" }
        val input = checkNotNull(contentResolver.openInputStream(uri)) { "cannot open $uri" }
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        target.path
    }

    /** The flights uploaded to WeGlide, by IGC file name (WeGlide's answer). */
    val weGlideUploads = object : UploadStore {
        private val preferences by lazy { getSharedPreferences("weglide_uploads", MODE_PRIVATE) }

        override suspend fun load() = withContext(Dispatchers.IO) {
            preferences.all.mapNotNull { (name, value) ->
                (value as? String)?.let {
                    try {
                        name to WeGlideFlight.parse(it)
                    } catch (_: Exception) {
                        null
                    }
                }
            }.toMap()
        }

        override suspend fun save(name: String, flight: WeGlideFlight) {
            withContext(Dispatchers.IO) {
                preferences.edit().putString(name, flight.encode()).commit()
            }
        }
    }

    /** The pilot chose the plane and crew since the app started. */
    var crewChosen = false

    /**
     * The first start's setup (units, pilot and glider, maps) is done
     * or was put off: it is not offered again.  An install that has a
     * profile, planes or data files already needs none.
     */
    var setupDone: Boolean
        get() = uiPreferences.getBoolean("setup_done", false) ||
            listOf("profiles", "planes", "maps", "airspace").any {
                File(xcsoarDataDir, it).list()?.isNotEmpty() == true
            }
        set(value) = uiPreferences.edit().putBoolean("setup_done", value).apply()

    /** The pilot has set up a plane (XCSoarData/planes, upstream's layout). */
    val hasPlanes: Boolean
        get() = File(xcsoarDataDir, "planes").list()
            ?.any { it.endsWith(".xcp", ignoreCase = true) } == true

    /**
     * Where the phone is, by country code, best first: its mobile
     * network, its SIM, its language's country.  No permission needed.
     */
    fun regionHints(): List<String> {
        val phone = getSystemService(TelephonyManager::class.java)
        return listOfNotNull(phone?.networkCountryIso, phone?.simCountryIso,
                             Locale.getDefault().country)
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * The phone's last known position from any provider (no new fix is
     * asked for); null without the location permission or a fix.
     */
    fun lastKnownPosition(): GeoPosition? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
                             Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return null
        val manager = getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.let { GeoPosition(it.latitude, it.longitude) }
        } catch (_: SecurityException) {
            null
        }
    }

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

    /**
     * Copy a flight the user picked for a replay into the cache,
     * keeping its name (its extension tells IGC from NMEA); the
     * previous one goes.
     *
     * @return the path of the copy
     */
    suspend fun importReplayFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val folder = File(cacheDir, "replay")
        folder.deleteRecursively()
        val target = File(folder.apply { mkdirs() }, displayName(uri))
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
