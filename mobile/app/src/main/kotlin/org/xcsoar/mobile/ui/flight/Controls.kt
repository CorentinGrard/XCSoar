// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.UnitGroup
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * Cockpit controls are at least 56 dp and act on release: Compose's
 * `clickable` fires on lift-off and cancels when the finger slides off
 * (doc/architecture.rst, "Touch interaction").
 */
private val TOUCH = 56.dp

/** How long a stepper's value takes to slide to the next one. */
private const val STEP_MILLIS = 150

/**
 * MacCready − value + stepper, in the pilot's vertical speed unit.
 *
 * @param onChange receives the new value, m/s
 */
@Composable
fun MacCreadyControl(
    macCready: Double?,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val unit = Format.unit(UnitGroup.VERTICAL_SPEED).name
    val text = macCready?.let { Format.macCready(it).text } ?: Format.INVALID
    Stepper("MacCready", "MC · $unit", text, "$text $unit",
            canDecrease = macCready != null && macCready > 0,
            canIncrease = macCready != null && macCready < 5,
            onDecrease = { macCready?.let { onChange(Format.stepVerticalSpeed(it, -1)) } },
            onIncrease = { macCready?.let { onChange(Format.stepVerticalSpeed(it, +1)) } },
            modifier = modifier)
}

/**
 * − value + stepper.
 *
 * @param name what the value is, for the buttons' labels ("MacCready")
 * @param spoken the value as a screen reader says it ("1.5 m/s")
 */
@Composable
fun Stepper(
    name: String,
    caption: String,
    text: String,
    spoken: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    // the new value slides in from the side of the button pressed
    var up by remember { mutableStateOf(true) }
    Row(
        modifier = modifier
            .background(colors.panel, RoundedCornerShape(12.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StepButton("Decrease $name", plus = false, enabled = canDecrease) {
            up = false
            onDecrease()
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally,
               modifier = Modifier.clearAndSetSemantics { contentDescription = "$name $spoken" }) {
            Caption(caption)
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    val sign = if (up) 1 else -1
                    (slideInVertically(tween(STEP_MILLIS)) { sign * it } +
                        fadeIn(tween(STEP_MILLIS))) togetherWith
                        (slideOutVertically(tween(STEP_MILLIS)) { -sign * it } +
                            fadeOut(tween(STEP_MILLIS)))
                },
                label = "stepper value",
            ) { value ->
                Text(value, color = colors.text, style = XcsTheme.numberStyle,
                     fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1)
            }
        }
        StepButton("Increase $name", plus = true, enabled = canIncrease) {
            up = true
            onIncrease()
        }
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
 * A row of segments, one selected: the whole segment height is the
 * touch target; the pill is inset.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    Row(
        modifier = modifier
            .height(TOUCH)
            .background(colors.panel, RoundedCornerShape(12.dp))
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = isSelected, role = Role.Tab) { onSelect(index) }
                    .padding(4.dp)
                    .background(if (isSelected) colors.selected else Color.Transparent,
                                RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (isSelected) colors.onSelected else colors.text,
                     fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** Opens the flight menu: a tile of its own, under the thumb. */
@Composable
fun MenuTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    Column(
        modifier = modifier
            .heightIn(min = TOUCH)
            .background(colors.selected, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Open menu", onClick = onClick)
            .semantics { contentDescription = "Menu" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.5.dp.toPx()
            for (y in listOf(0.2f, 0.5f, 0.8f))
                drawLine(colors.onSelected, Offset(stroke, size.height * y),
                         Offset(size.width - stroke, size.height * y), stroke, StrokeCap.Round)
        }
        Caption("Menu", color = colors.onSelected)
    }
}

/** A white button floating over the map. */
@Composable
private fun Modifier.mapButton(): Modifier {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(16.dp)
    return this
        .shadow(8.dp, shape, ambientColor = colors.text, spotColor = colors.text)
        .background(colors.card, shape)
        .border(1.dp, colors.panelBorder, shape)
        .clip(shape)
}

/** Map zoom: − and + side by side. */
@Composable
fun ZoomButtons(onZoom: (steps: Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    Row(modifier.mapButton(), verticalAlignment = Alignment.CenterVertically) {
        for ((label, steps) in listOf("Zoom out" to 1, "Zoom in" to -1)) {
            if (steps < 0)
                Box(Modifier.width(1.dp).height(32.dp).background(colors.panelBorder))
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
}

/** Shown after the pilot panned the map: back to following the aircraft. */
@Composable
fun CentreButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    Box(
        modifier
            .size(TOUCH)
            .background(colors.selected, RoundedCornerShape(16.dp))
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
 * Where north is on a turned map: a mark turned with the map.  It is
 * not a button; the orientation is chosen in the menu.
 *
 * @param mapAngle the map's rotation (degrees, the direction shown at
 * the top)
 */
@Composable
fun NorthMark(mapAngle: Double, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    Column(
        modifier
            .size(TOUCH)
            .mapButton()
            .clearAndSetSemantics { contentDescription = "North" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            rotate(-mapAngle.toFloat()) {
                drawPath(Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width * 0.9f, size.height * 0.8f)
                    lineTo(size.width * 0.1f, size.height * 0.8f)
                    close()
                }, colors.text)
            }
        }
        Text("N", color = colors.text, style = XcsTheme.numberStyle,
             fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** In a thermal: set MacCready to its average climb (updraft data, sky blue). */
@Composable
fun SetMacCreadyButton(lift: Double, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    val value = Format.macCready(Format.stepVerticalSpeed(lift, 0).coerceIn(0.0, 5.0))
    Column(
        modifier
            .heightIn(min = TOUCH)
            .shadow(8.dp, RoundedCornerShape(16.dp), ambientColor = colors.text,
                    spotColor = colors.text)
            .background(colors.updraft, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Set MacCready to ${value.text}",
                       onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SET MC", color = colors.onSelected, fontSize = 11.sp,
             fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, maxLines = 1)
        Text(value.text, color = colors.onSelected, style = XcsTheme.numberStyle,
             fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1)
    }
}
