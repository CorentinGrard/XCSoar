// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * The permission manager handed to XCSoar's Java I/O classes (e.g.
 * [InternalGPS]).  Their [PermissionManager] interface is
 * package-private, which is why this class lives in package org.xcsoar.
 *
 * The visible activity registers a [requester] that shows the system
 * permission dialog; without one, requests are answered when the app
 * comes to the foreground again.
 */
internal class AppPermissionManager(private val context: Context) : PermissionManager {
    /** Shows the system dialog for one permission and reports the result. */
    fun interface Requester {
        fun request(permission: String, onResult: (Boolean) -> Unit)
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Pair<String, PermissionManager.PermissionHandler>>()

    @Volatile
    var requester: Requester? = null
        set(value) {
            field = value
            if (value != null)
                main.post { flushPending() }
        }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun requestPermission(permission: String,
                                   handler: PermissionManager.PermissionHandler): Boolean {
        if (isGranted(permission))
            return true

        main.post {
            val r = requester
            if (r == null)
                pending += permission to handler
            else
                r.request(permission) { handler.onRequestPermissionsResult(it) }
        }
        return false
    }

    override fun cancelRequestPermission(handler: PermissionManager.PermissionHandler) {
        main.post { pending.removeAll { it.second === handler } }
    }

    private fun flushPending() {
        val r = requester ?: return
        val requests = pending.toList()
        pending.clear()
        for ((permission, handler) in requests)
            r.request(permission) { handler.onRequestPermissionsResult(it) }
    }

    override fun areLocationPermissionsGranted(): Boolean {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION))
            return false
        // FlightService (a location foreground service) keeps the
        // updates flowing in the background: no background location
        return true
    }

    override fun isNotificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || isGranted(Manifest.permission.POST_NOTIFICATIONS)

    override fun requestAllLocationPermissionsDirect() {
        requestPermission(Manifest.permission.ACCESS_FINE_LOCATION) {}
    }

    override fun requestNotificationPermissionDirect() {
        if (Build.VERSION.SDK_INT >= 33)
            requestPermission(Manifest.permission.POST_NOTIFICATIONS) {}
    }

    override fun suppressPermissionDialogs() {
    }

    override fun onDisclosureResult(accepted: Boolean) {
    }
}
