// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.settings

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.ui.flight.CalloutSettings
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the voice callouts page (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class VoiceScreenshotTest {
    @Test
    fun voice() = captureRoboImage("src/test/screenshots/voice_callouts.png") {
        XcsTheme(dark = false) {
            VoiceScreen(CalloutSettings(climbs = false), available = true, {}, {}, {})
        }
    }

    @Test
    fun noVoice() = captureRoboImage("src/test/screenshots/voice_unavailable.png") {
        XcsTheme(dark = true) {
            VoiceScreen(CalloutSettings(), available = false, {}, {}, {})
        }
    }
}
