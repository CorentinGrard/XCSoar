// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.Layout
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
import org.xcsoar.mobile.ui.Format
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
        Text(valueWithUnit(value, 28.sp, valueColor ?: colors.text, colors.textSecondary),
             style = XcsTheme.numberStyle, maxLines = 1)
    }
}

/** The pressed look of a tile, and its hold fill, over the panel colour. */
private const val PRESSED_ALPHA = 0.08f
private const val FILL_ALPHA = 0.25f

/**
 * The title, and the comment at the end of its line with what the
 * title leaves.  A comment with a number is never cut ("178…" for
 * 1784 ft would read as another value): it is left out when it does
 * not fit.  Text (a waypoint name) ends with an ellipsis.
 */
@Composable
private fun CaptionLine(title: String, comment: String, commentColor: Color) {
    val cut = comment.none { it.isDigit() }
    Layout({
        Caption(title)
        Text(comment, color = commentColor, fontSize = 12.sp, maxLines = 1,
             overflow = TextOverflow.Ellipsis)
    }) { (titleText, commentText), constraints ->
        val gap = 6.dp.roundToPx()
        val t = titleText.measure(constraints.copy(minWidth = 0))
        val space = constraints.maxWidth - t.width - gap
        val c = commentText.takeIf { comment.isNotEmpty() && space > 0 }
            ?.measure(Constraints(maxWidth = if (cut) space else Constraints.Infinity))
            ?.takeIf { it.width <= space }
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
         letterSpacing = 0.06.em, maxLines = 1, modifier = modifier)
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
