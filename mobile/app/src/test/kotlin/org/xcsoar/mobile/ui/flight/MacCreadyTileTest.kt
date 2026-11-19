// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * MacCready is a tile: a tap opens its stepper over the map, which
 * closes a while after the last step; Menu is a button on the map.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class MacCreadyTileTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tileOpensTheStepper() {
        val set = mutableListOf<Double>()
        var menu = 0
        compose.setContent {
            XcsTheme(dark = false) {
                FlightContent(FakeXcsoarCore.syntheticState(120), null, circling = false,
                              onMacCreadyChange = { set += it }, onMenuOpen = { menu++ })
            }
        }
        compose.onNode(hasContentDescription("MC 1.0", substring = true))
            .performTouchInput { down(center); advanceEventTime(80); up() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Increase MacCready")
            .performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(1, set.size)
        assertEquals(FakeXcsoarCore.syntheticState(120).macCready + 0.1, set[0], 1e-6)
        compose.onRoot().captureRoboImage("src/test/screenshots/maccready_stepper.png")

        // a while after the last step it closes, and the map buttons are back
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Increase MacCready").assertCountEquals(0)
        compose.onNodeWithContentDescription("Menu").performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertTrue(menu > 0)
    }
}
