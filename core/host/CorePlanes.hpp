// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

/**
 * Planes and crew, without the UI: what XCSoar's plane list and plane
 * dialogs (src/Dialogs/Plane) and the logger's pilot settings do.
 * Planes are XCSoar's .xcp files; the active one is the profile's
 * "PlanePath".  Everything runs on the core main thread.
 */
namespace CorePlanes {

/**
 * The planes as JSON (UTF-8), sorted by registration:
 *
 *   {"active": "/…/planes/D-1234.xcp", "last_flown": "/…/D-1234.xcp",
 *    "planes": [{"path": "/…/planes/D-1234.xcp",
 *                "registration": "D-1234", "competition_id": "XY",
 *                "type": "LS 4", "polar_name": "LS-4",
 *                "weglide_type": 160, "double_seater": false}, ...]}
 *
 * "active" and "last_flown" are empty when there is none (or the file
 * is gone).
 */
std::string
List() noexcept;

/** XCSoar's built-in polars, by index: ["206 Hornet", ...]. */
std::string
ListPolars() noexcept;

/**
 * Create a plane (empty @p path) or change one.  @p polar is an index
 * into ListPolars(), or -1 to keep the plane's polar (a new plane needs
 * one).  A new plane is written to planes/<registration>.xcp.
 *
 * @return the plane's path, or an empty string for invalid arguments
 * or an I/O error
 */
std::string
Save(const char *path, const char *registration, const char *competition_id,
     const char *type, int polar, unsigned weglide_type,
     bool double_seater) noexcept;

/** Fly this plane: load it, as the plane list's "Activate" does. */
bool
Activate(const char *path) noexcept;

/** Delete a plane file; not the active one. */
bool
Delete(const char *path) noexcept;

/** Remember the active plane as the last one flown (on take-off). */
void
RecordTakeoff() noexcept;

/**
 * The crew written to the IGC file, and the co-pilots flown with
 * before (most recent first):
 *
 *   {"pilot": "Jane Doe", "copilot": "", "copilots": ["John Roe", ...]}
 */
std::string
DescribeCrew() noexcept;

/**
 * Set the pilot and co-pilot names for the next IGC files (empty
 * co-pilot: flying solo).  A co-pilot goes to the top of the recent
 * list.  A null pointer leaves that name unchanged.
 */
bool
SetCrew(const char *pilot, const char *copilot) noexcept;

} // namespace CorePlanes
