// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#include "net/client/WeGlide/Settings.hpp"

#include <string>

/**
 * WeGlide (https://www.weglide.org) without the UI: the settings of
 * WeGlideConfigPanel, the aircraft types of WeGlideTypePicker and the
 * flight upload of src/net/client/WeGlide/UploadIGCFile.cpp.
 *
 * Settings functions run on the core main thread.  The network
 * functions block: call them from a worker thread, never the core main
 * thread or the network thread.  They throw on network and server
 * errors, with WeGlide's message.
 */
namespace CoreWeGlide {

/** {"enabled": true, "pilot_id": 1234, "birthdate": "1980-05-31"} */
std::string
DescribeSettings() noexcept;

/**
 * Save the settings; @p birthdate is "YYYY-MM-DD" or empty.
 *
 * @return false if the date is not valid
 */
bool
SetSettings(bool enabled, unsigned pilot_id, const char *birthdate) noexcept;

/**
 * WeGlide's aircraft types from the downloaded list whose name contains
 * @p query (any case), at most @p max of them, by name:
 * [{"id": 160, "name": "LS 4"}, ...].  Empty before the list was
 * downloaded once (UpdateAircraftList()).
 */
std::string
SearchAircraft(const char *query, unsigned max) noexcept;

/** Download WeGlide's aircraft list into the cache.  Blocks. */
void
UpdateAircraftList();

/**
 * One aircraft type from WeGlide: {"id": 160, "name": "LS 4",
 * "double_seater": false, "kind": "GL", "sc_class": "ST"}.  Blocks.
 */
std::string
DescribeAircraft(unsigned id);

/**
 * What an upload needs from the core: the settings and WeGlide's
 * aircraft type for an IGC file, from the plane whose registration
 * the file's header names, else the active plane.  Core main thread.
 */
struct UploadRequest {
  WeGlideSettings settings;
  unsigned aircraft_id = 0;
};

/**
 * @return false if WeGlide is not configured (enabled, pilot ID, date
 * of birth)
 */
bool
PrepareUpload(const char *igc_path, UploadRequest &request) noexcept;

/**
 * Upload an IGC file.  Blocks.
 *
 * @return the flight WeGlide made of it: {"flight_id": 123456,
 * "url": "https://www.weglide.org/flight/123456", "date": "2026-10-02",
 * "pilot": "Jane Doe", "aircraft": "LS 4", "registration": "D-1234",
 * "competition_id": "XY"}
 */
std::string
Upload(const char *igc_path, const UploadRequest &request);

} // namespace CoreWeGlide
