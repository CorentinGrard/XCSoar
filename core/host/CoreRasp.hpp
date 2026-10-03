// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <memory>
#include <string>

class RaspStore;

/**
 * RASP forecasts on the map without the UI: the configured file
 * (profile key RaspFile, a "-rasp.dat" archive from XCSoar's
 * repository) and the field and time of RASPDialog, kept in
 * UIState::weather where the map renderer reads them.
 *
 * All functions run on the core main thread.
 */
namespace CoreRasp {

/** (Re)load the configured file; none configured: no store. */
void
Load() noexcept;

void
Deinitialise() noexcept;

/** The loaded store, for the map; null without a file. */
std::shared_ptr<RaspStore>
Get() noexcept;

/** The number of fields of the loaded file. */
unsigned
CountFields() noexcept;

/**
 * {"fields": [{"name": "wstar", "label": "W*", "help": "...",
 *              "times": ["09:00", ..., "18:00"]}, ...],
 *  "field": 0, "time": "13:00"}
 *
 * "label" and "help" are translated; "help" is left out where XCSoar
 * has none.  Times are local, every quarter hour the file has.
 * "field" is -1 when the map shows none, "time" null when it follows
 * the clock.
 */
std::string
Describe() noexcept;

/**
 * Show @p field (-1: none) at @p time "HH:MM" (local; nullptr or ""
 * follows the clock).
 *
 * @return false if the field or the time is not in the file
 */
bool
Select(int field, const char *time) noexcept;

} // namespace CoreRasp
