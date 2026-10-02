// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.TaskFileInfo
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskOp
import org.xcsoar.mobile.core.WaypointInfo
import org.xcsoar.mobile.core.XcsoarCore

data class TaskState(
    /** The active task, or the edited copy while [TaskInfo.editing]. */
    val task: TaskInfo? = null,
    /** The point whose zone is being changed (editing only). */
    val selected: Int? = null,
    /** The task files, once listed. */
    val files: List<TaskFileInfo>? = null,
    /** Something the pilot asked for did not work. */
    val message: String? = null,
)

/**
 * XCSoar's task manager: the pilot edits a copy of the task, which
 * becomes the active one on [done].  Without an edit, the active task
 * is shown and can be advanced or restarted.
 */
class TaskViewModel(private val core: XcsoarCore) : ViewModel() {
    private val stateFlow = MutableStateFlow(TaskState())
    val state: StateFlow<TaskState> = stateFlow.asStateFlow()

    private val editing get() = stateFlow.value.task?.editing == true

    private var job: Job? = null

    /** Shown: read the task, and the active one again while flying it. */
    fun open() {
        job?.cancel()
        job = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_MS)
            }
        }
    }

    fun close() {
        job?.cancel()
    }

    private suspend fun refresh() {
        val task = try {
            core.task(editing) ?: if (editing) core.task(false) else null
        } catch (_: Exception) {
            null
        }
        stateFlow.update { it.copy(task = task) }
    }

    /**
     * Run one change, then show the result: [op]'s message (null for
     * none), or [refused] if it failed.
     */
    private fun run(refused: String, op: suspend () -> String?) {
        viewModelScope.launch {
            val message = try {
                op()
            } catch (_: Exception) {
                refused
            }
            stateFlow.update { it.copy(message = message) }
            refresh()
        }
    }

    private fun edit(op: TaskOp, index: Int = 0, value: Double = 0.0,
                     refused: String = "Not possible for this task type") =
        run(refused) { core.editTask(op, index, value); null }

    fun dismissMessage() = stateFlow.update { it.copy(message = null) }

    /* the active task */

    fun beginEdit() = run("Cannot edit the task") {
        core.editTask(TaskOp.BEGIN)
        stateFlow.update { it.copy(task = core.task(true), selected = null) }
        null
    }

    fun next() = edit(TaskOp.ADVANCE, value = 1.0, refused = "No next point")
    fun previous() = edit(TaskOp.ADVANCE, value = -1.0, refused = "No previous point")
    fun restart() = edit(TaskOp.RESTART)

    /* editing */

    fun cancel() = run("") {
        core.editTask(TaskOp.CANCEL)
        stateFlow.update { it.copy(task = core.task(false), selected = null) }
        null
    }

    /** Make the edited task the active one, unless it is not valid. */
    fun done() {
        viewModelScope.launch {
            try {
                core.editTask(TaskOp.COMMIT)
                stateFlow.update { it.copy(task = core.task(false), selected = null,
                                           message = null) }
            } catch (_: Exception) {
                val errors = core.task(true)?.errors?.trim().orEmpty()
                stateFlow.update {
                    it.copy(message = errors.ifEmpty { "The task is not valid" })
                }
                refresh()
            }
        }
    }

    fun add(waypoint: WaypointInfo) =
        edit(TaskOp.APPEND, value = waypoint.id.toDouble(), refused = "The task is full")

    fun select(index: Int?) = stateFlow.update {
        it.copy(selected = if (it.selected == index) null else index)
    }

    fun remove(index: Int) {
        stateFlow.update { it.copy(selected = null) }
        edit(TaskOp.REMOVE, index)
    }

    fun moveUp(index: Int) {
        if (index == 0) return
        stateFlow.update { it.copy(selected = index - 1) }
        edit(TaskOp.SWAP, index - 1)
    }

    fun moveDown(index: Int) {
        stateFlow.update { it.copy(selected = index + 1) }
        edit(TaskOp.SWAP, index)
    }

    fun clear() {
        stateFlow.update { it.copy(selected = null) }
        edit(TaskOp.CLEAR)
    }

    fun setType(type: Int) = edit(TaskOp.SET_TYPE, value = type.toDouble())

    fun setPointType(index: Int, type: Int) =
        edit(TaskOp.SET_POINT_TYPE, index, type.toDouble())

    /** One step bigger (+1) or smaller (-1), see [radiusStep]. */
    fun changeRadius(index: Int, direction: Int) {
        val radius = stateFlow.value.task?.points?.getOrNull(index)?.radius ?: return
        val step = radiusStep(if (direction > 0) radius else radius - 1)
        val value = (radius + direction * step).coerceAtLeast(MIN_RADIUS)
        edit(TaskOp.SET_RADIUS, index, value)
    }

    /** ± [AAT_TIME_STEP] seconds. */
    fun changeMinTime(direction: Int) {
        val time = stateFlow.value.task?.aatMinTime ?: return
        edit(TaskOp.SET_AAT_MIN_TIME,
             value = (time + direction * AAT_TIME_STEP).coerceIn(0.0, MAX_AAT_TIME))
    }

    /* files */

    fun listFiles() {
        stateFlow.update { it.copy(files = null) }
        viewModelScope.launch {
            val files = try {
                core.taskFiles()
            } catch (_: Exception) {
                emptyList()
            }
            stateFlow.update { it.copy(files = files) }
        }
    }

    fun load(file: TaskFileInfo) = run("${file.name}: cannot load the task") {
        core.loadTask(file)
        stateFlow.update { it.copy(task = core.task(true), selected = null) }
        null
    }

    /** Save the edited task as XCSoarData/tasks/<name>.tsk. */
    fun save(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.any { it == '/' || it == '\\' }) {
            stateFlow.update { it.copy(message = "Not a file name: $name") }
            return
        }
        run("Cannot save $clean") {
            core.saveTask(clean)
            "Saved as $clean"
        }
    }

    companion object {
        private const val REFRESH_MS = 3000L
        private const val MIN_RADIUS = 100.0
        const val AAT_TIME_STEP = 15 * 60.0
        private const val MAX_AAT_TIME = 12 * 3600.0

        /** Zone size steps: 100 m below 1 km, 500 m below 5 km, else 1 km. */
        fun radiusStep(radius: Double) = when {
            radius < 1000 -> 100.0
            radius < 5000 -> 500.0
            else -> 1000.0
        }
    }
}
