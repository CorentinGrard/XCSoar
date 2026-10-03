// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

/**
 * NOTAMs without the UI: the settings of NOTAMConfigPanel (same
 * profile keys, applied the same way) and the list of NOTAMList.
 * NOTAMGlue (NetComponents) downloads them; they become airspaces, so
 * the map draws them and the warnings include them.
 *
 * All functions run on the core main thread.
 */
namespace CoreNotam {

/** The cached NOTAMs as airspaces, like Startup's AfterStartup().
    Call once the threads run. */
void
LoadCached() noexcept;

/**
 * {"enabled": false, "radius_km": 50, "refresh_interval_min": 30,
 *  "show_only_effective": true, "show_ifr": false,
 *  "max_radius_m": 100000, "hidden_qcodes": "QA QK QN QOL QOA QOBTT"}
 */
std::string
DescribeSettings() noexcept;

/**
 * Change the settings: JSON shaped like DescribeSettings(), every value
 * optional.  Ranges: radius 1..185 km, refresh 0..240 min (0: manual),
 * max radius 0..1000000 m (0: no limit), hidden Q-codes at most 255
 * bytes.  Switching off removes the NOTAMs from the airspace;
 * switching on or a new radius downloads them again.
 *
 * @return false (nothing changed) if malformed or out of range
 */
bool
SetSettings(const char *json) noexcept;

/**
 * {"loading": false, "updated": "2026-10-03T19:20:00Z",
 *  "total": 42,
 *  "notams": [{"number": "A1234/26", "location": "LFMM",
 *              "text": "...", "start": "...", "end": "...",
 *              "permanent": false, "active": true,
 *              "lower": "SFC", "upper": "FL095",
 *              "distance": 12000.0}, ...]}
 *
 * The NOTAMs the settings show (the airspace filter), nearest first;
 * "distance" (m) only with a GPS fix.  "total" counts all loaded.
 * Times are UTC, ISO 8601.
 */
std::string
Describe() noexcept;

/** Download the NOTAMs around the aircraft now.  @return false
    without a fix or when switched off */
bool
Refresh() noexcept;

} // namespace CoreNotam
