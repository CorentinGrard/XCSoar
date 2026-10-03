// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.FinalGlide
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.abs

/** Full scale of the vario bar, ± m/s. */
private const val VARIO_RANGE = 5.0

/** Altitude difference that fills the final glide bar, m. */
private const val FINAL_GLIDE_RANGE = 500.0

/** Width of the bars on the map's edges. */
val EDGE_BAR_WIDTH = 56.dp

/**
 * The vario on the map's left edge: the total-energy vario as a number
 * and as a bar from zero (lift green, sink neutral), a sky blue marker
 * for the 30 s average, and netto underneath.
 */
@Composable
fun VarioBar(
    vario: Double?,
    average: Double?,
    netto: Double?,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    val varioText = Format.vario(vario)
    val averageText = Format.vario(average)
    val nettoText = Format.vario(netto)
    val lift = vario != null && vario >= 0.05
    EdgeCard(modifier.clearAndSetSemantics {
        contentDescription = "Vario ${varioText.text}, average ${averageText.text}, " +
            "netto ${nettoText.text} ${varioText.unit}"
    }) {
        // the colour must agree with the rounded number
        BarValue(varioText, if (lift) colors.lift else colors.text, 24)
        BarTrack(vario?.takeIf { abs(it) >= 0.05 }, VARIO_RANGE,
                 if (lift) colors.lift else colors.sink, average, Modifier.weight(1f))
        Caption("Netto")
        Text(nettoText.text, color = colors.text, style = XcsTheme.numberStyle,
             fontSize = 16.sp, maxLines = 1)
    }
}

/**
 * Final glide to the task finish on the map's right edge: the altitude
 * difference, above (safe) or below (caution), and a bar from zero.
 */
@Composable
fun FinalGlideBar(finalGlide: FinalGlide?, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    val difference = finalGlide?.altitudeDifference
    val value = Format.altitudeDifference(difference)
    val color = difference?.let { if (it >= 0) colors.safe else colors.caution }
    EdgeCard(modifier.clearAndSetSemantics {
        contentDescription = "Final glide ${value.text} ${value.unit}"
    }) {
        Caption("Final")
        BarValue(value, color ?: colors.text, 20)
        BarTrack(difference, FINAL_GLIDE_RANGE, color ?: colors.text, null,
                 Modifier.weight(1f))
    }
}

/** A narrow white card floating over the map. */
@Composable
private fun EdgeCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .width(EDGE_BAR_WIDTH)
            .shadow(8.dp, shape, ambientColor = colors.text, spotColor = colors.text)
            .background(colors.card, shape)
            .border(1.dp, colors.panelBorder, shape)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** The number over its unit, both centred in the narrow card. */
@Composable
private fun BarValue(value: Format.Value, color: Color, size: Int) {
    val colors = XcsTheme.colors
    Text(value.text, color = color, style = XcsTheme.numberStyle, fontWeight = FontWeight.Bold,
         fontSize = size.sp, maxLines = 1)
    Text(value.unit, color = colors.textSecondary, fontSize = 11.sp,
         fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/**
 * An upright track centred on zero: [value] fills up or down from the
 * zero line (clipped at ± [range]); [marker] is drawn across it.
 */
@Composable
private fun BarTrack(value: Double?, range: Double, fill: Color, marker: Double?,
                     modifier: Modifier) {
    val colors = XcsTheme.colors
    Canvas(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val track = 10.dp.toPx()
        val left = (size.width - track) / 2
        val centre = size.height / 2
        fun y(v: Double) = centre - (v.coerceIn(-range, range) / range * centre).toFloat()

        drawRoundRect(colors.panelBorder, Offset(left, 0f), Size(track, size.height),
                      CornerRadius(track / 2))
        if (value != null) {
            val end = y(value)
            drawRect(fill, Offset(left, minOf(centre, end)), Size(track, abs(end - centre)))
        }
        if (marker != null) {
            val h = 4.dp.toPx()
            val w = 32.dp.toPx()
            drawRoundRect(colors.updraft, Offset((size.width - w) / 2, y(marker) - h / 2),
                          Size(w, h), CornerRadius(h / 2))
        }
        val zero = 2.dp.toPx()
        val zeroWidth = 28.dp.toPx()
        drawRect(colors.text, Offset((size.width - zeroWidth) / 2, centre - zero / 2),
                 Size(zeroWidth, zero))
    }
}
