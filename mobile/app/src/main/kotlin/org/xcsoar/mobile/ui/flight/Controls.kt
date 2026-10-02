// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.FinalGlide
import org.xcsoar.mobile.core.MapOrientation
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.abs

/**
 * Cockpit controls are at least 56 dp and act on release: Compose's
 * `clickable` fires on lift-off and cancels when the finger slides off
 * (doc/architecture.rst, "Touch interaction").
 */
private val TOUCH = 56.dp

/** Altitude difference that fills the final glide bar, m. */
private const val FINAL_GLIDE_RANGE = 500.0

/** MacCready − value + stepper. */
@Composable
fun MacCreadyControl(
    macCready: Double?,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    val text = macCready?.let { Format.macCready(it).text } ?: Format.INVALID
    Row(
        modifier = modifier
            .background(colors.panel, RoundedCornerShape(12.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StepButton("Decrease MacCready", plus = false, enabled = macCready != null) { onChange(-0.1) }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
               modifier = Modifier.clearAndSetSemantics { contentDescription = "MacCready $text m/s" }) {
            Caption("MC · m/s")
            Text(text, color = colors.text, style = XcsTheme.numberStyle,
                 fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1)
        }
        StepButton("Increase MacCready", plus = true, enabled = macCready != null) { onChange(+0.1) }
    }
}

@Composable
private fun StepButton(label: String, plus: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(TOUCH)
            .alpha(if (enabled) 1f else 0.4f)
            .background(colors.control, shape)
            .border(1.dp, colors.panelBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.75.dp.toPx()
            val c = size.width / 2
            drawLine(colors.text, Offset(0f, c), Offset(size.width, c), stroke, StrokeCap.Round)
            if (plus)
                drawLine(colors.text, Offset(c, 0f), Offset(c, size.height), stroke, StrokeCap.Round)
        }
    }
}

/**
 * Final glide to the task finish: a small bar (up = above glide, down =
 * below) and the altitude difference in safe or caution colour.
 */
@Composable
fun FinalGlideTile(finalGlide: FinalGlide?, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    val difference = finalGlide?.altitudeDifference
    val value = Format.altitudeDifference(difference)
    val color = difference?.let { if (it >= 0) colors.safe else colors.caution }
    Row(
        modifier = modifier
            .background(colors.panel, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = "Final glide ${value.text} ${value.unit}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val track = colors.panelBorder
        val mark = colors.text
        Canvas(Modifier.width(12.dp).height(44.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(3.dp.toPx()))
            val centre = size.height / 2
            if (difference != null && color != null) {
                val h = (abs(difference).coerceAtMost(FINAL_GLIDE_RANGE) / FINAL_GLIDE_RANGE
                         * centre).toFloat()
                val top = if (difference >= 0) centre - h else centre
                drawRect(color, Offset(0f, top), Size(size.width, h))
            }
            val line = 2.dp.toPx()
            drawRect(mark, Offset(0f, centre - line / 2), Size(size.width, line))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Caption("Final glide")
            Text(valueWithUnit(value, 26.sp, color ?: colors.text, colors.textSecondary),
                 style = XcsTheme.numberStyle, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * Cruise / Circling switch.  It follows the glide computer's flight mode;
 * a tap shows the other layout until the next mode change.
 */
@Composable
fun FlightModeSwitch(
    circling: Boolean,
    onSelect: (circling: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    Row(
        modifier = modifier
            .height(TOUCH)
            .background(colors.panel, RoundedCornerShape(12.dp))
            .selectableGroup(),
    ) {
        ModeSegment("Cruise", selected = !circling, selectedColor = colors.selected,
                    Modifier.weight(1f)) { onSelect(false) }
        ModeSegment("Circling", selected = circling, selectedColor = colors.lift,
                    Modifier.weight(1f)) { onSelect(true) }
    }
}

@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = XcsTheme.colors
    Box(
        // the whole segment height is the touch target; the pill is inset
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(4.dp)
            .background(if (selected) selectedColor else Color.Transparent,
                        RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) colors.onSelected else colors.text,
             fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** An action in the flight menu; disabled items stay visible. */
data class MenuAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

@Composable
fun FlightMenuButton(actions: List<MenuAction>, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            modifier = Modifier
                .size(TOUCH)
                .background(colors.selected, RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Open menu") { open = true }
                .semantics { contentDescription = "Menu" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = 2.25.dp.toPx()
                for (y in listOf(0.2f, 0.5f, 0.8f))
                    drawLine(colors.onSelected, Offset(stroke, size.height * y),
                             Offset(size.width - stroke, size.height * y), stroke, StrokeCap.Round)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label, fontSize = 16.sp) },
                    enabled = action.enabled,
                    onClick = { open = false; action.onClick() },
                    modifier = Modifier.height(TOUCH),
                )
            }
        }
    }
}

/** Map zoom: + over −, like the design's map controls. */
@Composable
fun ZoomButtons(onZoom: (steps: Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .background(colors.card, shape)
            .border(1.dp, colors.panelBorder, shape),
    ) {
        for ((label, steps) in listOf("Zoom in" to -1, "Zoom out" to 1))
            Box(
                Modifier
                    .size(TOUCH)
                    .clickable(role = Role.Button, onClickLabel = label) { onZoom(steps) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    val stroke = 2.75.dp.toPx()
                    val c = size.width / 2
                    drawLine(colors.text, Offset(0f, c), Offset(size.width, c), stroke,
                             StrokeCap.Round)
                    if (steps < 0)
                        drawLine(colors.text, Offset(c, 0f), Offset(c, size.height), stroke,
                                 StrokeCap.Round)
                }
            }
    }
}

/** Shown after the pilot panned the map: back to following the aircraft. */
@Composable
fun CentreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    Box(
        modifier
            .size(TOUCH)
            .background(colors.selected, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Centre on aircraft", onClick = onClick)
            .semantics { contentDescription = "Centre on aircraft" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 2.5.dp.toPx()
            val c = center
            drawCircle(colors.onSelected, size.width * 0.3f, c,
                       style = Stroke(stroke))
            drawCircle(colors.onSelected, size.width * 0.1f, c)
            for ((a, b) in listOf(Offset(c.x, 0f) to Offset(c.x, size.height * 0.2f),
                                  Offset(c.x, size.height * 0.8f) to Offset(c.x, size.height),
                                  Offset(0f, c.y) to Offset(size.width * 0.2f, c.y),
                                  Offset(size.width * 0.8f, c.y) to Offset(size.width, c.y)))
                drawLine(colors.onSelected, a, b, stroke, StrokeCap.Round)
        }
    }
}

/**
 * Map orientation: a north mark turned to where north is on the map,
 * over the reference the map turns with ("N", "TRK", "TGT"...).
 *
 * @param mapAngle the map's rotation (degrees, the direction shown at
 * the top); null when unknown, which hides the mark
 */
@Composable
fun OrientationButton(orientation: MapOrientation, mapAngle: Double?, onClick: () -> Unit,
                      modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    val (label, description) = when (orientation) {
        MapOrientation.NORTH_UP -> "N" to "north up"
        MapOrientation.TRACK_UP -> "TRK" to "track up"
        MapOrientation.TARGET_UP -> "TGT" to "target up"
        MapOrientation.HEADING_UP -> "HDG" to "heading up"
        MapOrientation.WIND_UP -> "WIND" to "wind up"
    }
    Column(
        modifier
            .size(TOUCH)
            .background(colors.card, CircleShape)
            .border(1.dp, colors.panelBorder, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Change map orientation",
                       onClick = onClick)
            .semantics { contentDescription = "Map orientation: $description" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            if (mapAngle == null) return@Canvas
            rotate(-mapAngle.toFloat()) {
                drawPath(Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width * 0.9f, size.height * 0.8f)
                    lineTo(size.width * 0.1f, size.height * 0.8f)
                    close()
                }, colors.warning)
            }
        }
        Text(label, color = colors.text, style = XcsTheme.numberStyle,
             fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
