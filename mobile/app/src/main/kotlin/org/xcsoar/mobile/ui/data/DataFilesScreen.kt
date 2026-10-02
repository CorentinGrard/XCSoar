// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.data

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.DataStatus
import org.xcsoar.mobile.core.FileStatus
import org.xcsoar.mobile.core.MapStatus
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun DataFilesScreen(
    viewModel: DataFilesViewModel,
    onChoose: (DataFile) -> Unit,
    onDownload: (DataFile) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onBack)
    DataFilesContent(state, onChoose, viewModel::remove, onBack, onDownload)
}

/**
 * The files XCSoar loads: one card each for the map, airspace and
 * waypoints, with what was loaded from them.  The same files as in
 * XCSoar, stored in the same XCSoarData folders.
 */
@Composable
fun DataFilesContent(
    state: DataFilesState,
    onChoose: (DataFile) -> Unit,
    onRemove: (DataFile) -> Unit,
    onBack: () -> Unit,
    onDownload: ((DataFile) -> Unit)? = null,
) {
    val colors = XcsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sheet)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Back", primary = false, onClick = onBack)
            Text("Data files", color = colors.text, fontSize = 24.sp,
                 fontWeight = FontWeight.Bold)
        }

        val status = state.status
        FileCard(
            title = "Map",
            formats = "XCSoar map (.xcm): terrain, roads, rivers, towns",
            files = status?.map?.files,
            loaded = status?.map?.let { if (it.terrain) "Terrain loaded" else null },
            busy = state.busy == DataFile.MAP,
            onChoose = { onChoose(DataFile.MAP) },
            onRemove = { onRemove(DataFile.MAP) },
            onDownload = onDownload?.let { { it(DataFile.MAP) } },
        )
        FileCard(
            title = "Airspace",
            formats = "OpenAir (.txt, .air) or Tim Newport-Peace (.sua)",
            files = status?.airspace?.files,
            loaded = status?.airspace?.let { count(it.count, "airspace", "airspaces") },
            busy = state.busy == DataFile.AIRSPACE,
            onChoose = { onChoose(DataFile.AIRSPACE) },
            onRemove = { onRemove(DataFile.AIRSPACE) },
            onDownload = onDownload?.let { { it(DataFile.AIRSPACE) } },
        )
        FileCard(
            title = "Waypoints",
            formats = "SeeYou (.cup), WinPilot (.dat) and other XCSoar formats",
            files = status?.waypoints?.files,
            loaded = status?.waypoints?.let { count(it.count, "waypoint", "waypoints") },
            busy = state.busy == DataFile.WAYPOINTS,
            onChoose = { onChoose(DataFile.WAYPOINTS) },
            onRemove = { onRemove(DataFile.WAYPOINTS) },
            onDownload = onDownload?.let { { it(DataFile.WAYPOINTS) } },
        )

        if (state.error != null)
            Text(state.error, color = colors.warning, fontSize = 16.sp)
        Text("Airspace and waypoints inside the map file are counted too. " +
             "Load errors are shown on the flight screen.",
             color = colors.textSecondary, fontSize = 14.sp)
    }
}

private fun count(n: Int, one: String, many: String) =
    if (n == 0) null else "$n ${if (n == 1) one else many} loaded"

@Composable
private fun FileCard(
    title: String,
    formats: String,
    files: List<String>?,
    loaded: String?,
    busy: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    val colors = XcsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Caption(title)
        val name = files?.joinToString { it.substringAfterLast('/') }?.ifEmpty { null }
        Text(name ?: "No file", color = if (name != null) colors.text else colors.textSecondary,
             fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2,
             overflow = TextOverflow.Ellipsis)
        Text(loaded ?: formats, color = if (loaded != null) colors.safe else colors.textSecondary,
             fontSize = 15.sp)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(32.dp), color = colors.text,
                                          strokeWidth = 3.dp)
                Text("Loading…", color = colors.textSecondary, fontSize = 16.sp)
            } else {
                ActionButton(if (name == null) "Choose file" else "Replace", primary = true,
                             onClick = onChoose)
                if (onDownload != null)
                    ActionButton("Download", primary = false, onClick = onDownload)
                if (name != null)
                    ActionButton("Remove", primary = false, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Box(
        Modifier
            .height(56.dp)
            .widthIn(min = 96.dp)
            .background(if (primary) colors.selected else Color.Transparent,
                        RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) colors.onSelected else colors.text,
             fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun DataFilesPreview() {
    XcsTheme(dark = false) {
        DataFilesContent(
            DataFilesState(DataStatus(
                MapStatus(listOf("/x/XCSoarData/maps/alps_hd.xcm"), terrain = true),
                FileStatus(listOf("/x/XCSoarData/airspace/france.txt"), 812),
                FileStatus(emptyList(), 0))),
            {}, {}, {})
    }
}
