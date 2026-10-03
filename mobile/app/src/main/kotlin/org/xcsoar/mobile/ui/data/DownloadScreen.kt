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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.RepositoryFile
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun DownloadScreen(viewModel: DownloadViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    BackHandler {
        viewModel.cancel()
        onDone()
    }
    DownloadContent(state, viewModel::search, viewModel::download) {
        viewModel.cancel()
        onDone()
    }
}

/** The repository's files of one kind, searchable; a tap downloads and uses one. */
@Composable
fun DownloadContent(
    state: DownloadState,
    onSearch: (String) -> Unit,
    onDownload: (RepositoryFile) -> Unit,
    onBack: () -> Unit,
) {
    val colors = XcsTheme.colors
    val what = when (state.kind) {
        DataFile.MAP -> "maps"
        DataFile.AIRSPACE -> "airspace"
        DataFile.WAYPOINTS -> "waypoints"
        DataFile.RASP -> "RASP forecasts"
    }
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
            Text("Download $what", color = colors.text, fontSize = 22.sp,
                 fontWeight = FontWeight.Bold)
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onSearch,
            singleLine = true,
            label = { Text("Search name or country code (fr, de…)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.selected, focusedLabelColor = colors.text,
                cursorColor = colors.text),
        )

        if (state.error != null)
            Text(state.error, color = colors.warning, fontSize = 16.sp)

        val files = state.files
        if (files == null) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), color = colors.text)
                Text("Loading XCSoar's file list…", color = colors.textSecondary, fontSize = 16.sp)
            }
        } else {
            LazyColumn(Modifier
                .fillMaxWidth()
                .background(colors.panel, RoundedCornerShape(14.dp))) {
                items(state.shown, key = { it.name }) { file ->
                    FileRow(file, state.downloading == file.name, state.progress,
                            enabled = state.downloading == null) { onDownload(file) }
                    HorizontalDivider(color = colors.panelBorder,
                                      modifier = Modifier.padding(horizontal = 14.dp))
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: RepositoryFile, downloading: Boolean, progress: Float,
                    enabled: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Column(Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button,
                       onClickLabel = "Download ${file.name}", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
           verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(file.name, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        val detail = listOfNotNull(file.area.uppercase().ifEmpty { null }, file.updated,
                                   file.description.ifEmpty { null }).joinToString(" · ")
        if (detail.isNotEmpty())
            Text(detail, color = colors.textSecondary, fontSize = 14.sp, maxLines = 2,
                 overflow = TextOverflow.Ellipsis)
        if (downloading) {
            if (progress >= 0)
                LinearProgressIndicator(progress = { progress }, color = colors.selected,
                                        trackColor = colors.panelBorder,
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            else
                LinearProgressIndicator(color = colors.selected, trackColor = colors.panelBorder,
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}
