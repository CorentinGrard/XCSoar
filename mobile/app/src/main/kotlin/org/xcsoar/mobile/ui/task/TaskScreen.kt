// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.ui.Format
import org.xcsoar.mobile.ui.PageLayout
import org.xcsoar.mobile.ui.flight.Caption
import org.xcsoar.mobile.ui.flight.Stepper
import org.xcsoar.mobile.ui.theme.XcsTheme

/** What the task screen can ask for; no-ops by default (previews, tests). */
class TaskActions(
    val beginEdit: () -> Unit = {},
    val cancel: () -> Unit = {},
    val done: () -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val restart: () -> Unit = {},
    val addPoint: () -> Unit = {},
    val select: (Int) -> Unit = {},
    val remove: (Int) -> Unit = {},
    val moveUp: (Int) -> Unit = {},
    val moveDown: (Int) -> Unit = {},
    val clear: () -> Unit = {},
    val setType: (Int) -> Unit = {},
    val setPointType: (Int, Int) -> Unit = { _, _ -> },
    val changeRadius: (Int, Int) -> Unit = { _, _ -> },
    val changeMinTime: (Int) -> Unit = {},
    val save: (String) -> Unit = {},
    val openFiles: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

@Composable
fun TaskScreen(viewModel: TaskViewModel, onAddPoint: () -> Unit, onOpenFiles: () -> Unit,
               onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.open()
        onDispose { viewModel.close() }
    }
    BackHandler(onBack = onBack)
    TaskContent(state, onBack, TaskActions(
        beginEdit = viewModel::beginEdit, cancel = viewModel::cancel, done = viewModel::done,
        next = viewModel::next, previous = viewModel::previous, restart = viewModel::restart,
        addPoint = onAddPoint, select = viewModel::select, remove = viewModel::remove,
        moveUp = viewModel::moveUp, moveDown = viewModel::moveDown, clear = viewModel::clear,
        setType = viewModel::setType, setPointType = viewModel::setPointType,
        changeRadius = viewModel::changeRadius, changeMinTime = viewModel::changeMinTime,
        save = viewModel::save, openFiles = onOpenFiles,
        dismissMessage = viewModel::dismissMessage,
    ))
}

/**
 * The task: the active one (fly to the next or previous point,
 * restart), or the copy being edited (points, zones, task type), which
 * replaces the active task on "Done".
 */
@Composable
fun TaskContent(state: TaskState, onBack: () -> Unit, actions: TaskActions = TaskActions()) {
    val colors = XcsTheme.colors
    val task = state.task
    val editing = task?.editing == true
    var saving by remember { mutableStateOf(false) }

    PageLayout(bottom = if (task == null) null else ({
        // the edit replaces the active task on "Done"
        if (editing)
            TaskButton("Done", primary = true, modifier = Modifier.weight(1f),
                       onClick = actions.done)
        else
            TaskButton("Edit", primary = true, modifier = Modifier.weight(1f),
                       onClick = actions.beginEdit)
    })) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) {
                TaskButton("Cancel", onClick = actions.cancel)
                Text("Edit task", color = colors.text, fontSize = 24.sp,
                     fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            } else {
                TaskButton("Back", onClick = onBack)
                Text("Task", color = colors.text, fontSize = 24.sp,
                     fontWeight = FontWeight.Bold)
            }
        }

        state.message?.let { message ->
            Text(message, color = colors.onAlert, fontSize = 16.sp,
                 modifier = Modifier
                     .fillMaxWidth()
                     .background(colors.cautionContainer, RoundedCornerShape(12.dp))
                     .clickable(onClickLabel = "Dismiss", onClick = actions.dismissMessage)
                     .padding(14.dp))
        }

        if (task == null) {
            Text("Starting…", color = colors.textSecondary, fontSize = 16.sp)
            return@PageLayout
        }

        Summary(task, editing, actions)

        if (task.points.isEmpty())
            Text(if (editing) "Add the start, the turn points and the finish, in order."
                 else "No task. Edit to build one from waypoints, or load a task file.",
                 color = colors.textSecondary, fontSize = 16.sp,
                 modifier = Modifier.padding(horizontal = 4.dp))

        task.points.forEachIndexed { index, point ->
            PointRow(index, point, task, editing, state.selected == index, actions)
        }

        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskButton("Add point", primary = true, modifier = Modifier.weight(1f),
                           onClick = actions.addPoint)
                TaskButton("Save…", enabled = task.points.isNotEmpty(),
                           outlined = true, modifier = Modifier.weight(1f)) { saving = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskButton("Load…", outlined = true, modifier = Modifier.weight(1f),
                           onClick = actions.openFiles)
                TaskButton("Clear", enabled = task.points.isNotEmpty(), outlined = true,
                           modifier = Modifier.weight(1f), onClick = actions.clear)
            }
        } else {
            if (task.points.isNotEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskButton("Previous", outlined = true, enabled = (task.active ?: 0) > 0,
                               modifier = Modifier.weight(1f), onClick = actions.previous)
                    TaskButton("Next", outlined = true,
                               enabled = (task.active ?: 0) < task.points.size - 1,
                               modifier = Modifier.weight(1f), onClick = actions.next)
                    TaskButton("Restart", outlined = true, modifier = Modifier.weight(1f),
                               onClick = actions.restart)
                }
            TaskButton("Load…", outlined = true, modifier = Modifier.fillMaxWidth()) {
                actions.beginEdit()
                actions.openFiles()
            }
        }
    }

    if (saving)
        SaveDialog(task?.name.orEmpty(), onSave = { saving = false; actions.save(it) },
                   onDismiss = { saving = false })
}

/** Task type, distance, area minimum time and what makes it invalid. */
@Composable
private fun Summary(task: TaskInfo, editing: Boolean, actions: TaskActions) {
    val colors = XcsTheme.colors
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .padding(14.dp),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caption(task.name.ifEmpty { "Task type" })
                Text(task.typeName, color = colors.text, fontSize = 20.sp,
                     fontWeight = FontWeight.Bold)
            }
            if (editing)
                TaskButton(if (choosing) "Close" else "Change", outlined = true) {
                    choosing = !choosing
                }
        }
        if (editing && choosing)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in task.types)
                    Choice(choice.name, choice.type == task.type) {
                        actions.setType(choice.type)
                        choosing = false
                    }
            }

        if (task.points.size >= 2) {
            val distance = Format.distance(task.distance)
            val text = if (task.distanceMin != null && task.distanceMax != null)
                "${Format.distance(task.distanceMin).text}–" +
                    "${Format.distance(task.distanceMax).text} ${distance.unit} " +
                    "(${distance.text} nominal)"
            else
                "${distance.text} ${distance.unit}"
            Text(text, color = colors.text, style = XcsTheme.numberStyle, fontSize = 22.sp)
        }

        task.aatMinTime?.let { time ->
            val value = Format.duration(time).text
            if (editing)
                Stepper("minimum time", "Minimum time · h:mm", value, value,
                        canDecrease = time > 0, canIncrease = true,
                        onDecrease = { actions.changeMinTime(-1) },
                        onIncrease = { actions.changeMinTime(+1) },
                        modifier = Modifier.fillMaxWidth())
            else
                Text("Minimum time $value", color = colors.textSecondary, fontSize = 15.sp)
        }

        if (editing && task.points.isNotEmpty() && !task.valid && task.errors.isNotBlank())
            Text(task.errors.trim(), color = colors.caution, fontSize = 15.sp)
    }
}

private fun pointLabel(index: Int, point: TaskPointInfo) = when (point.kind) {
    "start" -> "Start"
    "finish" -> "Finish"
    else -> "TP $index"
}

@Composable
private fun PointRow(index: Int, point: TaskPointInfo, task: TaskInfo, editing: Boolean,
                     selected: Boolean, actions: TaskActions) {
    val colors = XcsTheme.colors
    val flying = !editing && task.active == index
    Column(Modifier
               .fillMaxWidth()
               .background(colors.panel, RoundedCornerShape(14.dp))
               .then(if (flying) Modifier.border(2.dp, colors.task, RoundedCornerShape(14.dp))
                     else Modifier)) {
        Row(Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(if (editing) Modifier.clickable(onClickLabel = "Change ${point.name}") {
                    actions.select(index)
                } else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier
                    .size(36.dp)
                    .background(if (flying) colors.task else colors.control, CircleShape),
                contentAlignment = Alignment.Center) {
                Text(when (point.kind) { "start" -> "S"; "finish" -> "F"; else -> "$index" },
                     color = if (flying) colors.onSelected else colors.text,
                     fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(point.name, color = colors.text, fontSize = 18.sp,
                     fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                val size = point.radius?.let { r ->
                    Format.distance(r).let { " · ${it.text} ${it.unit}" }
                }.orEmpty()
                Text("${pointLabel(index, point)} · ${point.typeName}$size",
                     color = colors.textSecondary, fontSize = 14.sp, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
            }
            if (index > 0) {
                val leg = Format.distance(point.leg)
                Text("${leg.text} ${leg.unit}", color = colors.textSecondary,
                     style = XcsTheme.numberStyle, fontSize = 16.sp)
            }
        }

        if (editing && selected)
            PointEditor(index, point, task.points.size, actions)
    }
}

/** Zone type and size, order, removal of one point. */
@Composable
private fun PointEditor(index: Int, point: TaskPointInfo, count: Int, actions: TaskActions) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
           verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (point.types.size > 1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in point.types)
                    Choice(choice.name, choice.type == point.type) {
                        actions.setPointType(index, choice.type)
                    }
            }
        point.radius?.let { radius ->
            val size = Format.distance(radius)
            val value = size.text
            Stepper("zone size", if (point.typeName.contains("line", ignoreCase = true))
                        "Half width · ${size.unit}" else "Radius · ${size.unit}",
                    value, "$value ${size.unit}",
                    canDecrease = radius > 100, canIncrease = true,
                    onDecrease = { actions.changeRadius(index, -1) },
                    onIncrease = { actions.changeRadius(index, +1) },
                    modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskButton("Up", outlined = true, enabled = index > 0,
                       modifier = Modifier.weight(1f)) { actions.moveUp(index) }
            TaskButton("Down", outlined = true, enabled = index < count - 1,
                       modifier = Modifier.weight(1f)) { actions.moveDown(index) }
            TaskButton("Remove", outlined = true, modifier = Modifier.weight(1f)) {
                actions.remove(index)
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = XcsTheme.colors
    Box(Modifier
            .heightIn(min = 48.dp)
            .background(if (selected) colors.selected else colors.control,
                        RoundedCornerShape(10.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) colors.onSelected else colors.text,
             fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SaveDialog(name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In XCSoarData/tasks, as a .tsk file XCSoar reads too.")
                OutlinedTextField(text, { text = it }, singleLine = true,
                                  label = { Text("Name") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A 56 dp button: filled, outlined or plain text. */
@Composable
internal fun TaskButton(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    outlined: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = XcsTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Box(modifier
            .height(56.dp)
            .widthIn(min = 88.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(when {
                primary -> colors.selected
                outlined -> colors.control
                else -> Color.Transparent
            }, shape)
            .then(if (outlined) Modifier.border(1.dp, colors.panelBorder, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) colors.onSelected else colors.text,
             fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
