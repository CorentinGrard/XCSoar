// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

/**
 * The pilot's units, without the UI: what XCSoar's units settings
 * panel (src/Dialogs/Settings/Panels/UnitsConfigPanel.cpp) does.  The
 * app formats values itself with these tables (mobile/docs/DECISIONS.md
 * D9).  Everything runs on the core main thread.
 */
namespace CoreUnits {

/**
 * XCSoar's units and the current choice as JSON (UTF-8):
 *
 *   {"units": [{"unit": 1, "name": "km", "factor": 0.001,
 *               "offset": 0.0}, ...],
 *    "groups": [{"group": 1, "unit": 1, "choices": [3, 2, 1]}, ...],
 *    "presets": [{"name": "European"}, ...], "preset": 0}
 *
 * "unit" is XCSoar's Unit, "group" its UnitGroup; "preset" is the
 * preset the settings equal, -1 for none.
 */
std::string
Describe() noexcept;

/**
 * Change the unit of one group (wind speed follows aircraft speed, as
 * in XCSoar); applied at once and saved in the profile.
 *
 * @return false if the unit is not one of the group's choices
 */
bool
Set(unsigned group, unsigned unit) noexcept;

/** Load one of XCSoar's unit presets ("European", "British"...). */
bool
ApplyPreset(unsigned index) noexcept;

} // namespace CoreUnits
