// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.ChartPoint
import org.xcsoar.mobile.core.TileGraphic
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.analysis.ChartMapper
import org.xcsoar.mobile.ui.analysis.area
import org.xcsoar.mobile.ui.analysis.level
import org.xcsoar.mobile.ui.analysis.line
import org.xcsoar.mobile.ui.theme.XcsColors
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * One InfoBox: a small caption over a large value and its unit.  The
 * comment (e.g. the altitude in the other unit) shares the caption's
 * line, so every tile has the same height.
 *
 * [valueColor] is an optional functional colour (lift/sink, safe/caution);
 * leave it `null` for plain data, which stays monochrome.
 */
@Composable
fun InfoBox(
    title: String,
    value: Format.Value,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    comment: String = "",
    commentColor: Color? = null,
    onLongClick: (() -> Unit)? = null,
    /** What XCSoar's InfoBox opens when tapped. */
    onClick: (() -> Unit)? = null,
    /** A graphical InfoBox's drawing, in the value's place. */
    graphic: TileGraphic? = null,
) {
    val colors = XcsTheme.colors
    val spoken = "$title ${value.text} ${value.unit} $comment".trim()
    val shape = RoundedCornerShape(12.dp)
    val feedback = remember { HoldFeedback() }
    val haptic = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onLongClick)
    Column(
        modifier = modifier
            .background(colors.panel, shape)
            .then(if (onLongClick != null || onClick != null)
                      // tap or hold, committed on lift-off as XCSoar's InfoBoxes
                      Modifier
                          .pointerInput(Unit) {
                              detectTapOrHold(feedback, onTap = { tap?.invoke() },
                                              onHold = { hold?.invoke() },
                                              onArmed = {
                                                  if (hold != null)
                                                      haptic.performHapticFeedback(
                                                          HapticFeedbackType.LongPress)
                                              })
                          }
                          .clip(shape)
                          .drawWithContent {
                              if (feedback.pressed)
                                  drawRect(colors.text.copy(alpha = PRESSED_ALPHA))
                              // the hold's one-way fill, left to right
                              val fill = feedback.fill.value
                              if (fill > 0f && hold != null)
                                  drawRect(colors.selected.copy(alpha = FILL_ALPHA),
                                           size = size.copy(width = size.width * fill))
                              drawContent()
                          }
                  else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (onClick != null) {
                    role = Role.Button
                    onClick("Open") { onClick(); true }
                }
                if (onLongClick != null) onLongClick("Change tile") { onLongClick(); true }
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CaptionLine(title, comment, commentColor ?: colors.textSecondary)
        Box(Modifier.fillMaxWidth()) {
            // as high with a drawing as with a value
            Text(valueWithUnit(value, VALUE_SIZE, valueColor ?: colors.text,
                               colors.textSecondary),
                 style = XcsTheme.numberStyle.copy(fontSize = VALUE_SIZE), maxLines = 1)
            if (graphic != null)
                TileDrawing(graphic, Modifier.matchParentSize())
        }
    }
}

private val VALUE_SIZE = 28.sp

/**
 * What a graphical InfoBox's OnCustomPaint() draws, from the core's
 * data: a vario spark, the barogram, or an arrow.
 */
@Composable
private fun TileDrawing(graphic: TileGraphic, modifier: Modifier) {
    val colors = XcsTheme.colors
    when (graphic.kind) {
        TileGraphic.WIND_ARROW, TileGraphic.NEXT_ARROW ->
            Box(modifier, contentAlignment = Alignment.Center) {
                DirectionArrow(graphic.angle,
                               if (graphic.kind == TileGraphic.NEXT_ARROW) colors.task
                               else colors.text,
                               Modifier.aspectRatio(1f).fillMaxHeight())
            }
        TileGraphic.SPARK -> Canvas(modifier) { spark(graphic, colors) }
        TileGraphic.BAROGRAM -> Canvas(modifier) { barogram(graphic, colors) }
    }
}

/**
 * TraceHistoryRenderer::RenderVario(): the climb filled in the lift
 * colour, the sink in the sink colour, over the zero line, with the
 * MacCready setting dashed and a dot on the latest value.
 */
private fun DrawScope.spark(g: TileGraphic, colors: XcsColors) {
    val values = g.values
    if (values.isEmpty()) return

    // ScaleChart(): the values, zero, the gauge's range and MacCready
    val negative = g.scale.getOrElse(0) { 0.0 }
    val positive = g.scale.getOrElse(1) { 0.0 }
    var low = minOf(0.0, values.min())
    var high = maxOf(0.0, values.max())
    if (!(high > low)) high += 1
    if (g.center) {
        high = maxOf(high, -low, positive, -negative)
        low = -high
    } else {
        high = maxOf(high, positive)
        low = minOf(low, negative)
    }
    high = maxOf(high, g.macCready)
    val m = ChartMapper(0f, 0f, size.width, size.height,
                        xMax = (maxOf(g.capacity, values.size) - 1).coerceAtLeast(1).toDouble(),
                        yMin = low, yMax = high)

    fun fill(points: List<ChartPoint>, color: Color) =
        drawPath(Path().apply {
            moveTo(m.x(points.first().t), m.y(0.0))
            for (p in points) lineTo(m.x(p.t), m.y(p.y))
            lineTo(m.x(points.last().t), m.y(0.0))
            close()
        }, color)

    for (i in 1 until values.size) {
        val a = ChartPoint(i - 1.0, values[i - 1])
        val b = ChartPoint(i.toDouble(), values[i])
        if (a.y * b.y < 0) {
            // split where the line crosses zero
            val zero = ChartPoint(a.t + a.y / (a.y - b.y), 0.0)
            fill(listOf(a, zero), if (a.y > 0) colors.lift else colors.sink)
            fill(listOf(zero, b), if (b.y > 0) colors.lift else colors.sink)
        } else if (a.y + b.y != 0.0) {
            fill(listOf(a, b), if (a.y + b.y > 0) colors.lift else colors.sink)
        }
    }
    line(m, listOf(ChartPoint(m.xMin, 0.0), ChartPoint(m.xMax, 0.0)), colors.textSecondary,
         width = 1.dp.toPx())
    if (g.macCready > 0)
        level(m, g.macCready, colors.updraft)
    drawCircle(colors.text, 2.dp.toPx(), m.at(ChartPoint(values.size - 1.0, values.last())))
}

/** RenderBarographSpark(): the terrain shaded, the altitude, a dot now. */
private fun DrawScope.barogram(g: TileGraphic, colors: XcsColors) {
    val altitude = g.points
    if (altitude.size < 2) return
    val terrain = g.terrain
    val all = altitude + terrain
    val m = ChartMapper(0f, 0f, size.width, size.height,
                        xMin = altitude.first().t, xMax = maxOf(altitude.last().t,
                                                                altitude.first().t + 1e-6),
                        yMin = minOf(0.0, all.minOf { it.y }),
                        yMax = maxOf(all.maxOf { it.y }, 1.0))
    area(m, terrain, colors.textSecondary.copy(alpha = 0.25f))
    line(m, altitude, colors.text, width = 2f)
    drawCircle(colors.text, 2.dp.toPx(), m.at(altitude.last()))
}

/** The pressed look of a tile, and its hold fill, over the panel colour. */
private const val PRESSED_ALPHA = 0.08f
private const val FILL_ALPHA = 0.25f

/**
 * The title, and the comment at the end of its line.  A comment with
 * a number (the distance to the waypoint the title names) is data: it
 * is never cut ("178…" for 1784 ft would read as another value), and
 * the title ends with an ellipsis to leave it room.  Text (a waypoint
 * name) gets what the title leaves, with an ellipsis.
 */
@Composable
private fun CaptionLine(title: String, comment: String, commentColor: Color) {
    val number = comment.any { it.isDigit() }
    Layout({
        Caption(title)
        Text(comment, color = commentColor, fontSize = 12.sp, maxLines = 1,
             overflow = TextOverflow.Ellipsis)
    }) { (titleText, commentText), constraints ->
        val gap = 6.dp.roundToPx()
        val width = constraints.maxWidth
        val t: Placeable
        val c: Placeable?
        if (number) {
            c = commentText.measure(Constraints()).takeIf { it.width + gap < width }
            t = titleText.measure(Constraints(maxWidth = width - (c?.width?.plus(gap) ?: 0)))
        } else {
            t = titleText.measure(constraints.copy(minWidth = 0))
            val space = width - t.width - gap
            c = commentText.takeIf { comment.isNotEmpty() && space > 0 }
                ?.measure(Constraints(maxWidth = space))
        }
        layout(constraints.maxWidth, maxOf(t.height, c?.height ?: 0)) {
            t.place(0, 0)
            c?.place(constraints.maxWidth - c.width, (t.height - c.height) / 2)
        }
    }
}

/** Upper-case caption above a value. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier,
            color: Color = XcsTheme.colors.textSecondary) {
    Text(text.uppercase(), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
         letterSpacing = 0.06.em, maxLines = 1, softWrap = false,
         overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** "1842 m": the value large, the unit small and secondary. */
fun valueWithUnit(
    value: Format.Value,
    size: TextUnit,
    color: Color,
    unitColor: Color,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontSize = size, color = color)) { append(value.text) }
    if (value.unit.isNotEmpty())
        withStyle(SpanStyle(fontSize = 14.sp, color = unitColor)) { append(" " + value.unit) }
}
