// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.util.Locale

/**
 * Over the map while a flight is replayed: the replayed time (UTC),
 * pause, speed (a tap goes to the next of [ReplaySpeed.CHOICES]), skip
 * ten minutes and stop.  It takes the place of the "REPLAY" chip.
 *
 * @param timeUtc the replayed time, seconds since 1970
 */
@Composable
fun ReplayBar(
    timeUtc: Double?,
    speed: ReplaySpeed,
    onPause: (Boolean) -> Unit,
    onSpeed: (Double) -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    FloatingCard(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("REPLAY", color = colors.neutralSafe, fontSize = 12.sp,
                 fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, maxLines = 1)
            Text(timeUtc?.let(::utcTime) ?: "--:--:--", color = colors.text,
                 style = XcsTheme.numberStyle, fontSize = 18.sp, maxLines = 1)
        }
        BarButton(if (speed.paused) "Play replay" else "Pause replay",
                  { onPause(!speed.paused) }) {
            Canvas(Modifier.size(18.dp)) {
                if (speed.paused)
                    drawPath(Path().apply {
                        moveTo(size.width * 0.15f, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width * 0.15f, size.height)
                        close()
                    }, colors.text)
                else
                    for (x in listOf(0.15f, 0.6f))
                        drawRect(colors.text, Offset(size.width * x, 0f),
                                 Size(size.width * 0.25f, size.height))
            }
        }
        val label = "${speedText(speed.timeScale)}×"
        BarButton("Replay speed $label, faster", { onSpeed(nextSpeed(speed.timeScale)) }) {
            BarText(label)
        }
        BarButton("Skip 10 minutes", onSkip) { BarText("+10′") }
        BarButton("Stop replay", onStop) { BarText("Stop") }
    }
}

/** A 56 dp button of the bar; [label] is what a screen reader says. */
@Composable
private fun BarButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .height(56.dp)
            .widthIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BarText(text: String) {
    Text(text, color = XcsTheme.colors.text, style = XcsTheme.numberStyle, fontSize = 18.sp,
         fontWeight = FontWeight.Bold, maxLines = 1)
}

/** The next faster speed, back to the slowest after the fastest. */
internal fun nextSpeed(timeScale: Double): Double =
    ReplaySpeed.CHOICES.firstOrNull { it > timeScale } ?: ReplaySpeed.CHOICES.first()

/** "10", "0.5": no ".0" on whole speeds. */
internal fun speedText(timeScale: Double): String =
    if (timeScale % 1.0 == 0.0) timeScale.toLong().toString()
    else String.format(Locale.ROOT, "%.1f", timeScale)

/** "14:02:31": the time of day in UTC, like the times of an IGC file. */
internal fun utcTime(seconds: Double): String {
    val s = Math.floorMod(seconds.toLong(), 86_400L)
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}

@Preview(widthDp = 390)
@Composable
private fun ReplayBarPreview() {
    XcsTheme(dark = false) {
        Box(Modifier.padding(12.dp)) {
            ReplayBar(1_791_000_000.0, ReplaySpeed(), {}, {}, {}, {})
        }
    }
}
