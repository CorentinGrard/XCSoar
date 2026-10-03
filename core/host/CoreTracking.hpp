// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include <string>

/**
 * Live tracking without the UI: the settings of TrackingConfigPanel
 * (SkyLines, LiveTrack24) and CloudConfigPanel (XCSoar Cloud), saved
 * under the same profile keys.  The backend timer sends the positions
 * (TrackingGlue, created by the core's NetComponents).
 *
 * Both functions run on the core main thread.
 */
namespace CoreTracking {

/**
 * {"skylines": {"enabled": false, "roaming": true, "interval": 5,
 *               "traffic": false, "near_traffic": false, "key": "0"},
 *  "livetrack24": {"enabled": false, "server": "www.livetrack24.com",
 *                  "username": "", "password": "", "interval": 60,
 *                  "vehicle_type": 0, "vehicle_name": ""},
 *  "cloud": {"enabled": null, "show_traffic": true,
 *            "show_thermals": true, "roaming": true}}
 *
 * "key" is hexadecimal; cloud "enabled" is null until the pilot
 * answered.  Intervals are seconds (TrackingConfigPanel's list).
 */
std::string
Describe() noexcept;

/**
 * Change the settings: a JSON object shaped like Describe(), where
 * every section and every value is optional.  Turning XCSoar Cloud on
 * creates its key, like CloudConfigPanel.
 *
 * @return false (and nothing changed) if the JSON is malformed or a
 * value is out of range
 */
bool
Set(const char *json) noexcept;

} // namespace CoreTracking
