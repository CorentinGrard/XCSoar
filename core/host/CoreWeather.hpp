// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include "Weather/NOAAStore.hpp"

#include <string>
#include <vector>

/**
 * METAR and TAF of the pilot's weather stations (NOAA) without the UI:
 * the station list of dlgNOAAList, saved in the profile, and its
 * downloads.  The store is XCSoar's noaa_store, so the map shows the
 * stations too.
 *
 * Functions run on the core main thread, except Download(), which
 * blocks: call it from a worker thread.
 */
namespace CoreWeather {

/** Create the store and read the stations from the profile. */
void
Initialise() noexcept;

void
Deinitialise() noexcept;

/**
 * [{"code": "LFMT", "name": "Montpellier", "metar": "LFMT 031830Z ...",
 *   "metar_time": "2026-10-03T18:30:00Z", "taf": "...",
 *   "taf_time": "...", "qnh": 1021.0, "wind_bearing": 220,
 *   "wind_speed": 4.1, "temperature": 291.15, "dew_point": 285.15,
 *   "visibility": 9999, "cavok": false,
 *   "text": "LFMT ...\nWind: ...\n"}]
 *
 * Units: hPa, degrees, m/s, Kelvin, m.  Values METAR did not give are
 * left out, and "metar"/"taf" until downloaded.  "text" is XCSoar's
 * decoded report (NOAAFormatter, in the user's units and language).
 */
std::string
Describe() noexcept;

/**
 * Add a station by its four letter ICAO code (any case).
 *
 * @return false if the code is not valid or the station is already
 * there
 */
bool
Add(const char *code) noexcept;

/** @return false if there is no such station */
bool
Remove(const char *code) noexcept;

/** The codes of the stations, to download. */
std::vector<std::string>
Codes() noexcept;

/** Download METAR and TAF of one station.  Blocks. */
NOAAStore::Item
Download(const char *code);

/** Keep a download (if the station is still there). */
void
Store(const NOAAStore::Item &item) noexcept;

} // namespace CoreWeather
