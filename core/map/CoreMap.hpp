// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

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
