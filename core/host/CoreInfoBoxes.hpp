// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

/**
 * The flight screen's tiles: XCSoar's InfoBoxes (src/InfoBoxes/Content)
 * without their windows.  Each tile shows one InfoBox type; the app has
 * a layout for cruise and one for circling, TILE_COUNT tiles each,
 * saved in the profile.  Everything runs on the core main thread.
 */
namespace CoreInfoBoxes {

static constexpr unsigned LAYOUT_COUNT = 2;
static constexpr unsigned TILE_COUNT = 6;

/**
 * The InfoBox types a tile can show (not the graphical ones), by name:
 * [{"id": 0, "name": "Altitude GPS", "caption": "Alt GPS",
 *   "description": "..."}, ...], translated.
 */
std::string
DescribeTypes() noexcept;

/** The tiles' types: {"layouts": [[cruise ids], [circling ids]]}. */
std::string
DescribeLayouts() noexcept;

/** Show InfoBox @p type in a tile; false for invalid arguments. */
bool
SetTile(unsigned layout, unsigned tile, unsigned type) noexcept;

/**
 * The tiles of @p layout now, as XCSoar's InfoBoxes show them:
 * [{"type": 0, "title": "Alt GPS", "value": "1234", "unit": "m",
 *   "comment": "", "color": 0, "comment_color": 0}, ...].
 * Colours are InfoBoxLook's: 0 none, 1 red, 2 blue, 3 green,
 * 4 yellow, 5 magenta.  Empty string for an invalid layout.
 */
std::string
Update(unsigned layout) noexcept;

/** Forget the content objects (on shutdown). */
void
Clear() noexcept;

} // namespace CoreInfoBoxes
