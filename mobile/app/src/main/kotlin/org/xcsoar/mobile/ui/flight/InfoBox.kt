// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * One InfoBox: a small caption over a large value and its unit.
 *
 * [valueColor] is an optional functional colour (lift/sink, safe/caution);
 * leave it `null` for plain data, which stays monochrome.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoBox(
    title: String,
    value: Format.Value,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    comment: String = "",
    commentColor: Color? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = XcsTheme.colors
    val spoken = "$title ${value.text} ${value.unit} $comment".trim()
    Column(
        modifier = modifier
            .background(colors.panel, RoundedCornerShape(12.dp))
            .then(if (onLongClick != null)
                      Modifier.combinedClickable(onClickLabel = null, onClick = {},
                                                 onLongClickLabel = "Change tile",
                                                 onLongClick = onLongClick)
                  else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Caption(title)
        Text(valueWithUnit(value, 28.sp, valueColor ?: colors.text, colors.textSecondary),
             style = XcsTheme.numberStyle, maxLines = 1)
        if (comment.isNotEmpty())
            Text(comment, color = commentColor ?: colors.textSecondary, fontSize = 13.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
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
