// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * One InfoBox: a title, a large value and its unit.  The value size
 * follows the box height so it stays readable on any screen.
 *
 * [accent] is an optional functional colour (e.g. lift/sink) for a thin
 * bar under the title; the value text itself stays monochrome.
 */
@Composable
fun InfoBox(
    title: String,
    value: Format.Value,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(6.dp)

    BoxWithConstraints(
        modifier = modifier
            .background(colors.panel, shape)
            .border(1.dp, colors.panelBorder, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics { contentDescription = "$title ${value.text} ${value.unit}" },
    ) {
        val valueSize = (maxHeight.value * 0.42f).coerceIn(18f, 64f).sp
        Column(modifier = Modifier.fillMaxWidth(),
               verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1)
            Box(Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .background(accent ?: Color.Transparent)
                .padding(top = 2.dp))
            Row(verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End) {
                Text(value.text,
                     color = colors.text,
                     fontSize = valueSize,
                     fontWeight = FontWeight.Bold,
                     maxLines = 1,
                     textAlign = TextAlign.End)
                Text(" " + value.unit,
                     color = colors.textSecondary,
                     fontSize = 13.sp,
                     modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}
