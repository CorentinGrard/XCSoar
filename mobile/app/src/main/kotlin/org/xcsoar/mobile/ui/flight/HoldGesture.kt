// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.xcsoar.mobile.ui.theme.XcsTheme

/** XCSoar's hold time (InfoBoxArrange::LONG_PRESS). */
private const val HOLD_MILLIS = 500L

/** Past this a press is a hold, not a tap: its fill shows (InfoBoxArrange::TAP). */
private const val TAP_MILLIS = 150L

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

/** What a tap-or-hold control shows while pressed. */
class HoldFeedback {
    /** The finger is down on the control (its pressed look). */
    var pressed by mutableStateOf(false)
        internal set

    /** 0…1 from [TAP_MILLIS] to [HOLD_MILLIS]: the hold's one-way fill. */
    val fill = Animatable(0f)
}

/**
 * A tap or a hold, as XCSoar's live InfoBoxes take them (doc/architecture.rst
 * "Touch interaction"): lifting before [HOLD_MILLIS] is a tap ([onTap]),
 * after it a hold ([onHold]); either commits on lift-off, and sliding
 * off the control cancels.  [onArmed] fires when the hold is armed (for
 * haptic feedback); [feedback] drives the pressed look and the fill.
 */
suspend fun PointerInputScope.detectTapOrHold(
    feedback: HoldFeedback,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onArmed: () -> Unit = {},
) = coroutineScope {
    awaitEachGesture {
        val down = awaitFirstDown()
        feedback.pressed = true
        val fill = launch {
            delay(TAP_MILLIS)
            feedback.fill.animateTo(1f, tween((HOLD_MILLIS - TAP_MILLIS).toInt(),
                                              easing = LinearEasing))
            onArmed()
        }

        var inside = true
        var lifted = false
        var held = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (change.isConsumed) break
            val p = change.position
            if (p.x < 0 || p.y < 0 || p.x > size.width || p.y > size.height)
                inside = false
            if (!change.pressed) {
                lifted = true
                held = change.uptimeMillis - down.uptimeMillis >= HOLD_MILLIS
                change.consume()
                break
            }
        }

        fill.cancel()
        feedback.pressed = false
        launch { feedback.fill.snapTo(0f) }
        if (lifted && inside) {
            if (held) onHold() else onTap()
        }
    }
}
