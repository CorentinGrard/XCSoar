// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the flight computer running with the screen off or another app
 * in front, like upstream's MyService: the internal GPS, the glide
 * computer, airspace warnings and (later) IGC logging must not pause in
 * flight.
 *
 * The core itself stays in [XcsoarApp] (one per process); this service
 * only holds the process at foreground priority, keeps location
 * updates flowing in the background (foregroundServiceType "location")
 * and the CPU awake (partial wake lock), and shows the notification
 * Android requires, which leads back to the flight screen.
 */
class FlightService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        // low importance: always visible, never a sound or a heads-up
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.flight_channel),
                                NotificationManager.IMPORTANCE_LOW))

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XCSoar:flight")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.flight_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                                          ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: RuntimeException) {
            // no location permission, or started from the background
            Log.w(TAG, "cannot run in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // renewed on every start (each time the app comes to the front)
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)

        // after the process died, the core is gone: no point restarting
        return START_NOT_STICKY
    }

    /** Swiping the app away from the recent apps ends the flight. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "FlightService"
        private const val CHANNEL_ID = "flight"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 16 * 60 * 60 * 1000L

        /**
         * Start the service if the location permission allows it (a
         * location foreground service needs it); call while the app is
         * in the foreground.
         */
        fun start(context: Context) {
            val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                 Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
            }
            if (!granted)
                return
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, FlightService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "cannot start", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlightService::class.java))
        }
    }
}
