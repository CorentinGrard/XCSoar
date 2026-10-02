// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.abs

/** Full scale of the vario bar, ± m/s. */
private const val VARIO_RANGE = 5.0

/**
 * The dark vario instrument: large total-energy vario, the 30 s average
 * and netto, and a bar centred on zero with a marker for the average.
 */
@Composable
fun VarioPanel(
    vario: Double?,
    average: Double?,
    netto: Double?,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    val varioText = Format.vario(vario)
    val averageText = Format.vario(average)
    val nettoText = Format.vario(netto)

    Column(
        modifier = modifier
            .background(colors.instrument, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)
            .clearAndSetSemantics {
                contentDescription = "Vario ${varioText.text}, average ${averageText.text}, " +
                    "netto ${nettoText.text} m/s"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caption("Vario · m/s", colors.instrumentTextSecondary)
                Text(varioText.text, color = climbColor(vario) ?: colors.instrumentText,
                     style = XcsTheme.numberStyle, fontWeight = FontWeight.Bold,
                     fontSize = 52.sp, lineHeight = 48.sp, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryValue("Avg 30 s", averageText.text, colors.instrumentUpdraft)
                SecondaryValue("Netto", nettoText.text, colors.instrumentText)
            }
        }
        VarioBar(vario, average)
    }
}

/** Lift or sink colour on the instrument; none for values shown as 0.0. */
@Composable
private fun climbColor(value: Double?): Color? = when {
    value == null -> null
    // the colour must agree with the rounded number
    value >= 0.05 -> XcsTheme.colors.instrumentLift
    value <= -0.05 -> XcsTheme.colors.instrumentSink
    else -> null
}

@Composable
private fun SecondaryValue(caption: String, text: String, color: Color) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Caption(caption, XcsTheme.colors.instrumentTextSecondary)
        Text(text, color = color, style = XcsTheme.numberStyle, fontSize = 26.sp, maxLines = 1)
    }
}

@Composable
private fun VarioBar(vario: Double?, average: Double?) {
    val colors = XcsTheme.colors
    val fill = climbColor(vario) ?: Color.Transparent
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val track = 10.dp.toPx()
        val top = (size.height - track) / 2
        val centre = size.width / 2
        fun x(value: Double) =
            centre + (value.coerceIn(-VARIO_RANGE, VARIO_RANGE) / VARIO_RANGE * centre).toFloat()

        drawRoundRect(colors.instrumentTrack, Offset(0f, top), Size(size.width, track),
                      CornerRadius(track / 2))
        if (vario != null && abs(vario) >= 0.05) {
            val end = x(vario)
            drawRect(fill, Offset(minOf(centre, end), top), Size(abs(end - centre), track))
        }
        if (average != null) {
            val w = 4.dp.toPx()
            drawRect(colors.instrumentUpdraft, Offset(x(average) - w / 2, 0f),
                     Size(w, size.height))
        }
        val zero = 2.dp.toPx()
        drawRect(colors.instrumentText, Offset(centre - zero / 2, 0f), Size(zero, size.height))
    }
}
