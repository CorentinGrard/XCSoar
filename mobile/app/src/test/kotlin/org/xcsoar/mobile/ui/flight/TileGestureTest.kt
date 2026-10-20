// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * A tile takes a tap or a hold as XCSoar's InfoBoxes do
 * (doc/architecture.rst "Touch interaction"): both commit on lift-off,
 * and sliding off cancels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class TileGestureTest {
    @get:Rule
    val compose = createComposeRule()

    private var taps = 0
    private var holds = 0

    private fun tile() {
        compose.setContent {
            XcsTheme(dark = false) {
                InfoBox("V GND", Format.Value("118", "km/h"), Modifier.size(120.dp, 72.dp),
                        onLongClick = { holds++ }, onClick = { taps++ })
            }
        }
    }

    private val node get() = compose.onNodeWithContentDescription("V GND 118 km/h")

    @Test
    fun tapOpens() {
        tile()
        node.performTouchInput { down(center); advanceEventTime(80); up() }
        compose.waitForIdle()
        assertEquals(1 to 0, taps to holds)
    }

    @Test
    fun holdCommitsOnLiftOff() {
        tile()
        node.performTouchInput { down(center); advanceEventTime(600) }
        compose.waitForIdle()
        // armed, not yet committed: the finger is still down
        assertEquals(0 to 0, taps to holds)
        // each block has its own event clock: lift 600 ms after its start
        node.performTouchInput { advanceEventTime(600); up() }
        compose.waitForIdle()
        assertEquals(0 to 1, taps to holds)
    }

    @Test
    fun slidingOffCancels() {
        tile()
        node.performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(center.copy(x = width * 3f))
            up()
        }
        compose.waitForIdle()
        assertEquals(0 to 0, taps to holds)
    }

    /** Lifting between the tap time and the hold is still a tap, as upstream. */
    @Test
    fun liftBeforeHoldIsATap() {
        tile()
        node.performTouchInput { down(center); advanceEventTime(300); up() }
        compose.waitForIdle()
        assertEquals(1 to 0, taps to holds)
    }

    /** Screen readers and tests reach both actions. */
    @Test
    fun accessibilityActions() {
        tile()
        node.performClick()
        node.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(1 to 1, taps to holds)
    }
}
