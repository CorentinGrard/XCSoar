// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

/**
 * The data behind XCSoar's analysis dialog (src/Dialogs/dlgAnalysis.cpp)
 * for the barograph, climb history, task speed and contest pages,
 * without drawing it.  Runs on the core main thread.
 */
namespace CoreAnalysis {

/**
 * The pages as JSON (UTF-8).  SI units; "t" is the flight time in
 * hours, the X axis of upstream's charts.  A line "trend" is
 * {"y0": y at t = 0, "gradient": per hour}, drawn across the chart.
 *
 *   {"flight_time": 2.5,
 *    "legs": [{"index": 0, "t": 0.4}, ...],
 *    "barograph": {"altitude": [[t, m], ...], "terrain": [[t, m], ...],
 *                  "base": [[t, m], ...], "base_trend": {...},
 *                  "ceiling": [[t, m], ...], "ceiling_trend": {...},
 *                  "working_band": [min, max], "ceiling_gradient": m/h},
 *    "climb": {"mac_cready": 1.5, "thermals": [[t, m/s, hours], ...],
 *              "trend": {...}, "average": m/s, "gradient": m/s per h},
 *    "task_speed": {"estimated": m/s, "speeds": [[t, m/s], ...],
 *                   "trend": {...}, "average": m/s},
 *    "contest": {"name": "OLC League",
 *                "results": [{"label": "", "distance": m, "score": pts,
 *                             "time": s, "speed": m/s,
 *                             "points": [[lat, lon], ...]}, ...],
 *                "trace": [[lat, lon], ...]}}
 *
 * Missing members mean what upstream's charts show as "no data":
 * "flight_time" before take-off, a page's series before it has two
 * points, "base"/"ceiling" (only their trend) before two climbs,
 * "task_speed" without an ordered task, a trend before two points,
 * "ceiling_gradient" before four climbs.  "legs" are the task points
 * reached, with the index upstream labels them with.  The contest
 * results are the ones upstream's caption shows for the contest chosen
 * in the settings; "terrain" only while there is terrain data (upstream
 * records 0 without it); "trace" is the flight, thinned to at most 500
 * points.
 */
std::string
Describe() noexcept;

} // namespace CoreAnalysis
