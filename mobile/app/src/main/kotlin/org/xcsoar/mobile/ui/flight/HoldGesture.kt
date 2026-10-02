// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import org.xcsoar.mobile.ui.theme.XcsTheme

/** XCSoar's hold time (InfoBoxArrange::LONG_PRESS). */
private const val HOLD_MILLIS = 500L

/**
 * A hold as doc/architecture.rst "Touch interaction" defines it: the
 * timer only arms the hold ([onArmed]); the action commits on lift-off
 * near the start point.  Moving past the touch slop (a pan or pinch,
 * which consumes the events) or releasing early is not a hold.
 */
suspend fun PointerInputScope.detectHoldRelease(
    onArmed: (Offset) -> Unit,
    onRelease: (position: Offset, commit: Boolean) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val early = withTimeoutOrNull(HOLD_MILLIS) { waitForUpOrCancellation(); true }
    if (early != null)
        return@awaitEachGesture

    onArmed(down.position)
    val up = waitForUpOrCancellation()
    val commit = up != null &&
        (up.position - down.position).getDistance() < viewConfiguration.touchSlop
    onRelease(down.position, commit)
}

/** Shows that a hold is armed: a ring around the finger. */
@Composable
fun HoldMarker(position: Offset) {
    val color = XcsTheme.colors.selected
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(color, 28.dp.toPx(), position, style = Stroke(4.dp.toPx()))
    }
}
