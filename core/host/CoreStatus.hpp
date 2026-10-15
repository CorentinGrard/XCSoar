// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

/**
 * What XCSoar's status dialog (src/Dialogs/dlgStatus.cpp) shows, as
 * text, without its widgets.  Runs on the core main thread.
 */
namespace CoreStatus {

/**
 * The pages, in the dialog's order, as JSON (UTF-8); labels and values
 * are translated and formatted in the pilot's units, as the panels do:
 *
 *   {"pages": [{"name": "flight", "title": "Flight",
 *               "rows": [{"label": "Altitude", "value": "642 m"}, ...]},
 *              ...],
 *    "location": [lat, lon]}
 *
 * Page names: flight, system, task, rules, times.  A value the panel
 * leaves empty is "".  "location" (degrees) only with a GPS fix, for
 * the flight page's "Share".
 */
std::string
Describe() noexcept;

} // namespace CoreStatus
