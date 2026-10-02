// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.task

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.TaskInfo
import org.xcsoar.mobile.core.TaskPointInfo
import org.xcsoar.mobile.core.TaskTypeChoice
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: the task screen (mobile/docs/ARCHITECTURE.md §6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class TaskScreenshotTest {
    private val turnTypes = listOf(TaskTypeChoice(3, "FAI quadrant"),
                                   TaskTypeChoice(8, "Turn point cylinder"),
                                   TaskTypeChoice(4, "Keyhole sector"))

    private val points = listOf(
        TaskPointInfo(1, "Grenoble Le Versoud", "start", 1, "Start line", 1000.0),
        TaskPointInfo(2, "Dent de Crolles", "turn", 8, "Turn point cylinder", 500.0,
                      leg = 14_200.0, types = turnTypes),
        TaskPointInfo(3, "Mont Granier", "turn", 8, "Turn point cylinder", 500.0,
                      leg = 18_600.0, types = turnTypes),
        TaskPointInfo(1, "Grenoble Le Versoud", "finish", 14, "Finish cylinder", 3000.0,
                      leg = 31_900.0),
    )

    @Test
    fun activeRacingTask() = captureRoboImage("src/test/screenshots/task_active.png") {
        XcsTheme(dark = false) {
            TaskContent(TaskState(TaskInfo(4, "Racing", valid = true, distance = 64_700.0,
                                           active = 2, points = points)), {})
        }
    }

    @Test
    fun editingAreaTask() = captureRoboImage("src/test/screenshots/task_edit.png") {
        XcsTheme(dark = false) {
            TaskContent(TaskState(
                TaskInfo(5, "AAT", editing = true, valid = true, distance = 64_700.0,
                         distanceMin = 58_000.0, distanceMax = 71_300.0, aatMinTime = 10_800.0,
                         types = listOf(TaskTypeChoice(4, "Racing"), TaskTypeChoice(5, "AAT")),
                         points = points),
                selected = 1), {})
        }
    }
}
