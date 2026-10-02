// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.TaskFileInfo
import org.xcsoar.mobile.ui.theme.XcsTheme

@Composable
fun TaskFilesScreen(viewModel: TaskViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.listFiles() }
    BackHandler(onBack = onBack)
    TaskFilesContent(state.files, { viewModel.load(it); onBack() }, onBack)
}

/** The tasks XCSoar finds in XCSoarData; a tap loads one for editing. */
@Composable
fun TaskFilesContent(files: List<TaskFileInfo>?, onLoad: (TaskFileInfo) -> Unit,
                     onBack: () -> Unit) {
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
            TaskButton("Back", onClick = onBack)
            Text("Load task", color = colors.text, fontSize = 24.sp,
                 fontWeight = FontWeight.Bold)
        }

        when {
            files == null -> Text("Looking for tasks…", color = colors.textSecondary,
                                  fontSize = 16.sp)
            files.isEmpty() -> Text(
                "No task files. Saved tasks go to XCSoarData/tasks; tasks in SeeYou " +
                    ".cup files there are found too.",
                color = colors.textSecondary, fontSize = 16.sp)
        }

        for (file in files.orEmpty())
            Text(file.name, color = colors.text, fontSize = 18.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = 2,
                 overflow = TextOverflow.Ellipsis,
                 modifier = Modifier
                     .fillMaxWidth()
                     .heightIn(min = 56.dp)
                     .background(colors.panel, RoundedCornerShape(14.dp))
                     .clickable(onClickLabel = "Load ${file.name}") { onLoad(file) }
                     .padding(horizontal = 14.dp, vertical = 16.dp))
    }
}
