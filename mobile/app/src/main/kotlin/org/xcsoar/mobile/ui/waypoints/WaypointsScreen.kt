// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.waypoints

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.WaypointFilter
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * @param onDone a waypoint was chosen (flown to or picked)
 * @param onBack left without choosing
 * @param onPick instead of "Go to", hand the tapped waypoint over (e.g.
 * to add it to the task); the screen then closes
 */
@Composable
fun WaypointsScreen(
    viewModel: WaypointsViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit = onDone,
    title: String = "Go to",
    onPick: ((WaypointInfo) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    BackHandler(onBack = onBack)
    val onTap: (WaypointInfo) -> Unit =
        if (onPick != null) { waypoint -> onPick(waypoint); onDone() } else viewModel::goto
    WaypointsContent(state, viewModel::search, viewModel::filter, onTap, onBack, title,
                     action = if (onPick != null) "Add" else "Go")
}

/**
 * Nearest first; each row's [action] button flies there (XCSoar's "Go
 * to") or picks it.  Only the button acts, so a bumpy tap on the list
 * does not change where the pilot flies.
 */
@Composable
fun WaypointsContent(
    state: WaypointsState,
    onSearch: (String) -> Unit,
    onFilter: (WaypointFilter) -> Unit,
    onGoto: (WaypointInfo) -> Unit,
    onBack: () -> Unit,
    title: String = "Go to",
    action: String = "Go",
) {
    val colors = XcsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier
                    .height(56.dp)
                    .widthIn(min = 96.dp)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center) {
                Text("Back", color = colors.text, fontSize = 16.sp,
                     fontWeight = FontWeight.SemiBold)
            }
            Text(title, color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        Row(Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(colors.panel, RoundedCornerShape(12.dp))
                .selectableGroup()) {
            for ((label, filter) in listOf("Landable" to WaypointFilter.LANDABLE,
                                           "Airports" to WaypointFilter.AIRPORT,
                                           "All" to WaypointFilter.ALL)) {
                val selected = state.filter == filter
                Box(Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected, role = Role.Tab) { onFilter(filter) }
                        .padding(4.dp)
                        .background(if (selected) colors.selected else Color.Transparent,
                                    RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) colors.onSelected else colors.text,
                         fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onSearch,
            singleLine = true,
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.selected, focusedLabelColor = colors.text,
                cursorColor = colors.text),
        )

        if (state.error != null)
            Text(state.error, color = colors.warning, fontSize = 16.sp)

        val waypoints = state.waypoints
        if (waypoints != null && waypoints.isEmpty())
            Text("No waypoint found. Load a map or waypoint file (Menu → Data files).",
                 color = colors.textSecondary, fontSize = 16.sp)
        LazyColumn(Modifier
            .fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(14.dp))) {
            items(waypoints.orEmpty(), key = { it.id }) { waypoint ->
                WaypointRow(waypoint, action) { onGoto(waypoint) }
                HorizontalDivider(color = colors.panelBorder,
                                  modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}

@Composable
private fun WaypointRow(waypoint: WaypointInfo, action: String, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Row(Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(waypoint.name, color = colors.text, fontSize = 17.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bearing = Format.bearing(waypoint.bearing)
            val elevation = waypoint.elevation?.let { Format.altitude(it) }
            Text(listOfNotNull(
                     when {
                         waypoint.airport -> "Airport"
                         waypoint.landable -> "Landable"
                         else -> null
                     },
                     waypoint.bearing?.let { "${bearing.text}${bearing.unit}" },
                     elevation?.let { "elev ${it.text} ${it.unit}" })
                     .joinToString(" · "),
                 color = colors.textSecondary, fontSize = 14.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End,
               verticalArrangement = Arrangement.spacedBy(2.dp)) {
            waypoint.distance?.let {
                val distance = Format.distance(it)
                Text("${distance.text} ${distance.unit}", color = colors.text,
                     style = XcsTheme.numberStyle, fontSize = 20.sp, maxLines = 1)
            }
            waypoint.arrival?.let { arrival ->
                val value = Format.altitudeDifference(arrival.toDouble())
                Text("${value.text} ${value.unit}",
                     color = if (waypoint.reachable == true) colors.safe else colors.caution,
                     style = XcsTheme.numberStyle, fontWeight = FontWeight.Bold,
                     fontSize = 17.sp, maxLines = 1)
            }
        }
        Box(Modifier
                .size(56.dp)
                .background(colors.selected, RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClickLabel = "$action ${waypoint.name}",
                           onClick = onClick)
                .semantics { contentDescription = "$action ${waypoint.name}" },
            contentAlignment = Alignment.Center) {
            Text(action, color = colors.onSelected, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
