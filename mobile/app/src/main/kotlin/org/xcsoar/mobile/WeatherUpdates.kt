// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.DataFile

/**
 * Keeps the weather fresh while the app runs: METAR and TAF every 30
 * minutes, and the RASP forecast once it is older than today (like
 * upstream's RaspDownloadGlue), but only on an unmetered network (the
 * file is tens of MB) and never in flight.  "Today's" on the weather
 * page downloads it on any network.
 */
class WeatherUpdates(private val app: XcsoarApp, scope: CoroutineScope) {
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var lastMetar = Long.MIN_VALUE / 2
    private var lastRaspAttempt = Long.MIN_VALUE / 2

    init {
        scope.launch {
            delay(START_DELAY_MS)
            while (true) {
                try {
                    check()
                } catch (e: Exception) {
                    // the core may not run yet; try again later
                    Log.d(TAG, "weather update: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun check() {
        val core = app.core ?: return
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true)
            return

        val now = SystemClock.elapsedRealtime()
        if (now - lastMetar >= METAR_INTERVAL_MS && core.weatherStations().isNotEmpty()) {
            lastMetar = now
            try {
                core.updateWeather()
                Log.i(TAG, "METAR and TAF updated")
            } catch (e: Exception) {
                Log.d(TAG, "METAR update: ${e.message}")
            }
        }

        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (!unmetered || core.flightState.value?.flying == true ||
            now - lastRaspAttempt < RASP_RETRY_MS || !core.raspInfo().outOfDate)
            return
        val path = core.dataStatus().rasp.files.firstOrNull() ?: return
        lastRaspAttempt = now
        Log.i(TAG, "downloading today's RASP forecast")
        app.updateDataFile(DataFile.RASP, path.substringAfterLast('/'))
    }

    private companion object {
        const val TAG = "XCSoar"
        const val START_DELAY_MS = 30_000L
        const val CHECK_INTERVAL_MS = 5 * 60_000L
        const val METAR_INTERVAL_MS = 30 * 60_000L
        /** After a failed or interrupted RASP download. */
        const val RASP_RETRY_MS = 60 * 60_000L
    }
}
