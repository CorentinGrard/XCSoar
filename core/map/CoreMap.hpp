// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

struct ANativeWindow;

/**
 * XCSoar's moving map (src/MapWindow), drawn by the core with OpenGL
 * ES into a surface the app supplies (mobile/docs/DECISIONS.md D6).
 *
 * Everything here runs on the core main thread, which owns the EGL
 * context, like the UI thread does in XCSoar's OpenGL builds.
 */
namespace CoreMap {

/**
 * Start drawing into @p window (it gains a reference), size in
 * pixels.  Replaces a previous surface.
 *
 * @return false on error (logged)
 */
bool
Attach(ANativeWindow *window, unsigned width, unsigned height,
       unsigned dpi) noexcept;

/** Stop drawing; the surface is about to be destroyed. */
void
Detach() noexcept;

bool
IsAttached() noexcept;

/** Where the aircraft is drawn, in pixels from the top left corner. */
void
SetAircraftPosition(int x, int y) noexcept;

/** Zoom in (negative) or out (positive) by steps of XCSoar's scale list. */
void
Zoom(int steps) noexcept;

/**
 * Move the map with the finger by (dx, dy) pixels and stop following
 * the aircraft.
 */
void
Pan(double dx, double dy) noexcept;

/** Zoom continuously: factor > 1 zooms in (pinch). */
void
Scale(double factor) noexcept;

/** Follow the aircraft again after Pan(). */
void
Follow() noexcept;

/**
 * Orientation of the map in cruise and circling, as XCSoar's
 * MapOrientation (stored in the profile like XCSoar does).
 */
void
SetOrientation(unsigned orientation) noexcept;

[[gnu::pure]]
unsigned
GetOrientation() noexcept;

/**
 * What is on the map around pixel (x, y), like GlueMapWindow's map item
 * list: a JSON array, nearest first.  Each item has "type" (location,
 * self, task, airspace, thermal, waypoint, traffic, other) and, where it
 * applies, "name", "detail", "class", "top", "base" (formatted like
 * XCSoar), "elevation" (m), "frequency" (MHz text), "landable".
 */
std::string
ItemsAt(int x, int y) noexcept;

/**
 * Display options of the map, as xcs_map_option (stored in the profile
 * under XCSoar's keys).  Returns false for an unknown option or value.
 */
bool
SetOption(unsigned option, int value) noexcept;

bool
GetOption(unsigned option, int &value) noexcept;

/** Redraw soon (at most a few times per second); for new data. */
void
Invalidate() noexcept;

/** The data files were loaded again: drop caches, reload topography. */
void
OnDataChanged() noexcept;

/** Free everything (before the data components go away). */
void
Deinitialise() noexcept;

/** Draw a frame with the latest blackboard (no-op if not attached). */
void
Render() noexcept;

} // namespace CoreMap
