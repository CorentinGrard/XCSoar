// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.NextWaypoint
import org.xcsoar.mobile.core.Wind
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme

/*
 * Overlays drawn over the map.  The map is north up, so every direction
 * here is a true bearing.
 */

/**
 * A white card floating over the map; with [elevated] false, a flat
 * panel on the instrument sheet instead.
 */
@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    elevated: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = XcsTheme.colors
    Row(
        modifier = modifier
            .then(if (elevated)
                Modifier
                    .shadow(8.dp, shape, ambientColor = colors.text, spotColor = colors.text)
                    .background(colors.card, shape)
                    .border(1.dp, colors.panelBorder, shape)
            else Modifier.background(colors.panel, shape)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The next waypoint: direction arrow, name, bearing, distance, and the
 * arrival height above (safe) or below (caution) the glide path.
 */
@Composable
fun NextWaypointCard(
    next: NextWaypoint?,
    modifier: Modifier = Modifier,
    timeRemaining: Double? = null,
    onClick: (() -> Unit)? = null,
    elevated: Boolean = true,
) {
    val colors = XcsTheme.colors
    val distance = Format.distance(next?.distance)
    val bearing = Format.bearing(next?.bearing)
    val arrival = Format.altitudeDifference(next?.altitudeDifference)
    val name = next?.name?.ifEmpty { null } ?: if (next == null) "No target" else Format.INVALID

    FloatingCard(
        modifier
            .then(if (onClick != null)
                Modifier.clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClickLabel = "Choose where to go",
                               onClick = onClick)
            else Modifier)
            .clearAndSetSemantics {
                contentDescription = if (next == null) "No target" else
                    "Next $name, ${distance.text} ${distance.unit}, bearing ${bearing.text}, " +
                        "arrival ${arrival.text} ${arrival.unit}"
            },
        elevated = elevated,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (next != null)
                Box(Modifier.size(44.dp).background(colors.selected, CircleShape),
                    contentAlignment = Alignment.Center) {
                    DirectionArrow(next.bearing, colors.onSelected, Modifier.size(24.dp))
                }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (next != null) Row(verticalAlignment = Alignment.CenterVertically,
                                      horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NEXT", color = colors.onSelected, fontSize = 11.sp,
                         fontWeight = FontWeight.Bold, letterSpacing = 0.08.em,
                         modifier = Modifier
                             .background(colors.task, RoundedCornerShape(5.dp))
                             .padding(horizontal = 6.dp, vertical = 1.dp))
                    val ete = timeRemaining?.let { " · ETE ${Format.duration(it).text}" } ?: ""
                    Text("${bearing.text}${bearing.unit}$ete", color = colors.textSecondary,
                         fontSize = 13.sp)
                }
                Text(name, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End,
                   verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(valueWithUnit(distance, 24.sp, colors.text, colors.textSecondary),
                     style = XcsTheme.numberStyle, maxLines = 1)
                Text(valueWithUnit(arrival, 18.sp,
                                   next?.let { if (it.altitudeDifference >= 0) colors.safe
                                               else colors.caution } ?: colors.text,
                                   colors.textSecondary),
                     style = XcsTheme.numberStyle, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** Wind: an arrow showing where it blows to, "270° · 16 km/h". */
@Composable
fun WindChip(wind: Wind?, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    val from = Format.bearing(wind?.bearing)
    val speed = Format.windSpeed(wind?.speed)
    val text = "${from.text}${from.unit} · ${speed.text} ${speed.unit}"
    FloatingCard(modifier.clearAndSetSemantics { contentDescription = "Wind from $text" },
                 RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).background(colors.panel, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                if (wind != null)
                    DirectionArrow(wind.bearing + 180, colors.text, Modifier.size(20.dp))
            }
            Text(text, color = colors.text, style = XcsTheme.numberStyle, fontSize = 18.sp,
                 maxLines = 1)
        }
    }
}

/** A short status word over the map ("REPLAY", "NO GPS"). */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    FloatingCard(modifier, RoundedCornerShape(12.dp)) {
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
             maxLines = 1, overflow = TextOverflow.Ellipsis,
             modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** An arrow pointing to [bearing] (degrees, north up). */
@Composable
private fun DirectionArrow(bearing: Double, color: Color, modifier: Modifier) {
    Canvas(modifier.rotate(bearing.toFloat())) {
        val w = size.width
        val stroke = Stroke(w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(w * 0.5f, w * 0.87f); lineTo(w * 0.5f, w * 0.13f)
            moveTo(w * 0.23f, w * 0.4f); lineTo(w * 0.5f, w * 0.13f); lineTo(w * 0.77f, w * 0.4f)
        }
        drawPath(path, color, style = stroke)
    }
}

/**
 * Stand-in for the moving map (M3): the ground colour and the glider
 * symbol turned to the track.  Nothing here pretends to be terrain.
 *
 * @param coveredTop pixels at the top hidden by cards; the glider is
 * centred in the area below
 */
@Composable
fun MapPlaceholder(track: Double?, coveredTop: Int, modifier: Modifier = Modifier) {
    val colors = XcsTheme.colors
    val halo = colors.sheet
    val ink = colors.text
    Canvas(modifier.background(colors.background)) {
        if (track == null) return@Canvas
        val s = 0.9f * density
        val top = coveredTop.toFloat().coerceAtMost(size.height)
        val center = Offset(size.width / 2, (top + size.height) / 2)
        rotate(track.toFloat(), center) {
            val glider = Path().apply {
                moveTo(center.x, center.y - 18 * s); lineTo(center.x, center.y + 22 * s)
                moveTo(center.x - 32 * s, center.y - 3 * s); lineTo(center.x + 32 * s, center.y - 3 * s)
                moveTo(center.x - 10 * s, center.y + 20 * s); lineTo(center.x + 10 * s, center.y + 20 * s)
            }
            drawPath(glider, halo, style = Stroke(9 * s, cap = StrokeCap.Round))
            drawPath(glider, ink, style = Stroke(4.5f * s, cap = StrokeCap.Round))
        }
    }
}
