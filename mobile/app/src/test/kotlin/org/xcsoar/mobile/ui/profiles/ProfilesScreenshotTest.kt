// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.profiles

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.ProfileInfo
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the profile list (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w390dp-h844dp-xxhdpi")
class ProfilesScreenshotTest {
    private val profiles = listOf(
        ProfileInfo("Club", "/x/profiles/Club.prf", password = true),
        ProfileInfo("default", "/x/profiles/default.prf", current = true),
    )

    @Test
    fun onTheGround() = captureRoboImage("src/test/screenshots/profiles.png") {
        XcsTheme(dark = false) {
            ProfilesContent(profiles, null, flying = false, {}, {}, {}, {}, {})
        }
    }

    /* in flight: no switching; an error from the last action */
    @Test
    fun inFlight() = captureRoboImage("src/test/screenshots/profiles_flying.png") {
        XcsTheme(dark = false) {
            ProfilesContent(profiles, "Not created: \"Club\" is taken or not a file name",
                            flying = true, {}, {}, {}, {}, {})
        }
    }
}
