// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * The phone's battery for XCSoar's Battery InfoBox: upstream's
 * [BatteryReceiver] hands each ACTION_BATTERY_CHANGED to the core
 * (src/Android/Battery.cpp), as XCSoar.java does.
 *
 * Lives in package org.xcsoar for the package-private [BatteryReceiver],
 * which is reused unchanged (mobile/docs/DECISIONS.md D13).
 */
object CoreBattery {
    /** Once, after the native library is loaded; the battery state is
        a sticky broadcast, so the first one arrives at once. */
    fun register(context: Context) {
        ContextCompat.registerReceiver(context.applicationContext, BatteryReceiver(),
                                       IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                                       ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
