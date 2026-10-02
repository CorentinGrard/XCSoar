// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.xcsoar.mobile.core.MapItemInfo
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * What is at the point of the map the pilot held: airspace with its
 * limits, waypoints, the terrain.  Not modal: the map stays usable
 * (ARCHITECTURE.md §1, no dialogs over the map in flight).
 */
@Composable
fun MapItemsCard(
    items: List<MapItemInfo>,
    onClose: () -> Unit,
    onGoto: (waypointId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = XcsTheme.colors
    FloatingCard(modifier, RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Caption("At this point", Modifier.weight(1f))
                Box(
                    Modifier
                        .size(56.dp)
                        .clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose)
                        .semantics { contentDescription = "Close" },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        val stroke = 2.5.dp.toPx()
                        drawLine(colors.text, Offset.Zero, Offset(size.width, size.height),
                                 stroke, StrokeCap.Round)
                        drawLine(colors.text, Offset(size.width, 0f), Offset(0f, size.height),
                                 stroke, StrokeCap.Round)
                    }
                }
            }
            Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                if (items.isEmpty())
                    Text("Nothing here", color = colors.textSecondary, fontSize = 16.sp,
                         modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
                items.forEachIndexed { i, item ->
                    if (i > 0)
                        HorizontalDivider(color = colors.panelBorder,
                                          modifier = Modifier.padding(horizontal = 14.dp))
                    val (title, detail) = describe(item)
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp),
                               verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, color = colors.text, fontSize = 17.sp,
                                 fontWeight = FontWeight.SemiBold, maxLines = 2,
                                 overflow = TextOverflow.Ellipsis)
                            if (detail.isNotEmpty())
                                Text(detail, color = colors.textSecondary, fontSize = 15.sp,
                                     maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        val waypointId = item.id
                        if (item.type == "waypoint" && waypointId != null)
                            Box(Modifier
                                    .padding(start = 8.dp)
                                    .height(48.dp)
                                    .widthIn(min = 72.dp)
                                    .background(colors.task, RoundedCornerShape(10.dp))
                                    .clickable(role = Role.Button,
                                               onClickLabel = "Go to ${item.name}") {
                                        onGoto(waypointId)
                                    }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center) {
                                Text("Go to", color = colors.onSelected, fontSize = 15.sp,
                                     fontWeight = FontWeight.SemiBold)
                            }
                    }
                }
            }
        }
    }
}

/** A title and a detail line for one item. */
private fun describe(item: MapItemInfo): Pair<String, String> {
    val elevation = item.elevation?.let { Format.altitude(it) }?.let { "${it.text} ${it.unit}" }
    return when (item.type) {
        "airspace" -> (item.name ?: "Airspace") to
            listOfNotNull(item.`class`, if (item.base != null && item.top != null)
                "${item.base} – ${item.top}" else null).joinToString(" · ")
        "waypoint" -> (item.name ?: "Waypoint") to
            listOfNotNull(if (item.landable == true) "Landable" else "Waypoint", elevation,
                          item.frequency?.let { "$it MHz" }, item.detail).joinToString(" · ")
        "location" -> "Terrain" to (elevation ?: "")
        "self" -> "Your glider" to ""
        "task" -> "Task point" to (item.name ?: "")
        "thermal" -> "Thermal" to ""
        "traffic" -> "FLARM traffic" to ""
        else -> (item.name ?: "Item") to ""
    }
}
