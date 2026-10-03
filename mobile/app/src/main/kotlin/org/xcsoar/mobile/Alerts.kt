// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.xcsoar.mobile.ui.flight.AirspaceAlert
import org.xcsoar.mobile.ui.flight.Alert

/**
 * Sound and vibration for alerts (XCSoar plays its "beep bweep" and
 * vibrates on a new airspace warning).  The alarm stream is used so the
 * alert is heard even with notifications silenced.
 */
class Alerts(context: Context) {
    private val tones = try {
        ToneGenerator(AudioManager.STREAM_ALARM, 100)
    } catch (_: RuntimeException) {
        null   // no audio hardware
    }

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        else
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    /** The tone and/or the vibration of [alert], as the pilot chose. */
    fun play(alert: AirspaceAlert) {
        when (alert.level) {
            Alert.WARNING -> {
                if (alert.sound)
                    tones?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
                if (alert.vibration)
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300),
                                                                     -1))
            }
            Alert.CAUTION -> {
                if (alert.sound)
                    tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                if (alert.vibration)
                    vibrator?.vibrate(VibrationEffect.createOneShot(250,
                                                                    VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    fun release() {
        tones?.release()
    }
}
