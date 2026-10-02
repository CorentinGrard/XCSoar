// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

/**
 * The task, without the UI: what XCSoar's task manager dialog
 * (src/Dialogs/Task/Manager) does.  The pilot edits a copy of the
 * active task, which replaces it on Commit(), so a half-built task
 * never guides the glider.
 *
 * Everything runs on the core main thread.  Functions returning bool
 * return false for an invalid argument or when the change is not
 * allowed (e.g. no edit in progress, a point type the task type does
 * not accept).
 */
namespace CoreTask {

/**
 * The task as JSON (UTF-8):
 *
 *   {"type": 4, "type_name": "Racing", "name": "", "editing": false,
 *    "valid": true, "errors": "", "distance": 123400.0,
 *    "distance_min": ..., "distance_max": ..., "aat_min_time": 10800,
 *    "active": 1,
 *    "types": [{"type": 0, "name": "FAI badges/records"}, ...],
 *    "points": [{"waypoint_id": 42, "name": "Anduze", "kind": "start",
 *                "type": 1, "type_name": "Start line",
 *                "radius": 1000.0, "leg": 0.0,
 *                "types": [{"type": 1, "name": "Start line"}, ...]}, ...]}
 *
 * "type" is XCSoar's TaskFactoryType, the points' "type" its
 * TaskPointFactoryType; "types" are the ones allowed there, with
 * XCSoar's names.  "radius" (half the length for lines) is missing for
 * zones without one; "distance_min/max" and "aat_min_time" only for
 * area tasks; "active" only for the active task.
 *
 * @param edited the task being edited (empty string if none)
 * instead of the active one
 */
std::string
Describe(bool edited) noexcept;

/** Start editing a copy of the active task (again, if already). */
void
BeginEdit() noexcept;

void
CancelEdit() noexcept;

/**
 * Make the edited task the active one and save it as the default
 * task, like TaskManagerDialog::Commit().  An empty task is allowed
 * (no task).
 *
 * @return false if the task is not valid (Describe(true) says why);
 * editing goes on
 */
bool
Commit() noexcept;

/** Append a waypoint as start, turn point or (after it) finish. */
bool
Append(unsigned waypoint_id) noexcept;

bool
Remove(unsigned index) noexcept;

/** Swap the points at index and index + 1. */
bool
Swap(unsigned index) noexcept;

/** Remove all points. */
bool
Clear() noexcept;

/** Change the task type (TaskFactoryType); points follow it. */
bool
SetType(unsigned type) noexcept;

/** Change a point's type (TaskPointFactoryType), keeping its size. */
bool
SetPointType(unsigned index, unsigned type) noexcept;

/** Set a zone's radius (lines: half their length), metres. */
bool
SetRadius(unsigned index, double radius) noexcept;

/** Minimum time of an area task, seconds. */
bool
SetAATMinTime(double seconds) noexcept;

/**
 * The task files XCSoar finds (.tsk files in XCSoarData/tasks and the
 * tasks in other files XCSoar reads, e.g. SeeYou .cup) as JSON:
 *
 *   [{"name": "Grenoble 300.tsk", "path": "/…/tasks/Grenoble 300.tsk",
 *     "index": 0}, ...]
 */
std::string
ListFiles() noexcept;

/** Load a task from a file into the editor (editing starts). */
bool
Load(const char *path, unsigned index) noexcept;

/**
 * Save the edited task as XCSoarData/tasks/<name>.tsk.
 *
 * @param name a file name without folder or extension
 */
bool
Save(const char *name) noexcept;

/** Go to the next (1) or previous (-1) point of the active task. */
bool
Advance(int offset) noexcept;

/** Restart the active task as if not flown yet (start again). */
void
Restart() noexcept;

/** Free the edited task; at shutdown. */
void
Deinitialise() noexcept;

} // namespace CoreTask
