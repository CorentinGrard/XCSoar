// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.AirspaceWarningInfo
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme
import kotlin.math.roundToInt

/**
 * The most severe airspace warning, over everything else on the map
 * (overlays stack alert > caution > info): red inside, orange before.
 * "Ack" acknowledges it until it changes, "Day" for the rest of the day.
 */
@Composable
fun AirspaceWarningBanner(
    warning: AirspaceWarningInfo,
    more: Int,
    onAcknowledge: (day: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    val background = if (warning.inside) colors.warningContainer else colors.cautionContainer
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .background(background, shape)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(headline(warning), color = colors.onAlert,
                 fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(warning.name, color = colors.onAlert, fontSize = 18.sp,
                 fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(warning.`class`, "${warning.base} – ${warning.top}",
                        if (more > 0) "+$more more" else "")
                     .filter { it.isNotBlank() }.joinToString(" · "),
                 color = colors.onAlert, fontSize = 14.sp, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
        }
        AckButton("Ack", "Acknowledge ${warning.name}") { onAcknowledge(false) }
        AckButton("Day", "Acknowledge ${warning.name} for today") { onAcknowledge(true) }
    }
}

/** "INSIDE", or "IN 1:25 · 1.2 KM" before entering. */
private fun headline(w: AirspaceWarningInfo): String {
    if (w.inside) return "INSIDE AIRSPACE"
    val parts = mutableListOf(if (w.state == "task") "TASK CROSSES" else "AIRSPACE AHEAD")
    w.time?.takeIf { it >= 0 }?.let {
        val s = it.roundToInt()
        parts += "in %d:%02d".format(s / 60, s % 60)
    }
    w.distance?.takeIf { it >= 0 }?.let {
        val d = Format.distance(it)
        parts += "${d.text} ${d.unit}"
    }
    return parts.joinToString(" · ").uppercase()
}

@Composable
private fun AckButton(label: String, description: String, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(56.dp)
            .widthIn(min = 56.dp)
            .border(2.dp, colors.onAlert, shape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.onAlert, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
