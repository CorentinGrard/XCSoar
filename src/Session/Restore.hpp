// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

class GlidePolar;
class TaskManager;
class Waypoints;

/*
 * Restoring the flight after a restart in flight, from the session
 * file that Session::Load() has read.  Both do nothing unless the file
 * says the aircraft was flying.
 */
namespace Session {

/**
 * Put the bugs, the ballast, the crew mass, the QNH, the forecast
 * temperature and the ATC reference back.
 */
void
RestoreComputer(GlidePolar &gp) noexcept;

/**
 * Go to the waypoint again, unless a task is active or the waypoint
 * is gone.
 */
void
RestoreGoto(TaskManager &task_manager, Waypoints &waypoints) noexcept;

} // namespace Session
