// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

class OperationEnvironment;

/**
 * Start XCSoar's backend without any user interface: load the
 * profile and data files, create the glide computer and devices, and
 * start the merge and calculation threads.
 *
 * This is the headless counterpart of Startup() in src/Startup.cpp
 * and follows the same order.  The caller must have set up the
 * process-wide globals first (data path, asio thread, network).
 *
 * @param open_devices false to leave all devices closed (replay,
 * analysis, tests)
 * @return true on success
 */
bool
CoreStartup(OperationEnvironment &operation, bool open_devices = true);

/**
 * Load the configured data files again after their profile keys
 * changed: the headless counterpart of the file handling in
 * SettingsLeave() (src/UtilsSettings.cpp).  A map file may contain
 * waypoints and airspace, so @p map reloads those too.  Runs on the
 * core main thread, with the calculation threads suspended.
 */
void
CoreReloadDataFiles(bool map, bool waypoints, bool airspace,
                    OperationEnvironment &operation) noexcept;

/**
 * What XCSoar's default input events (Data/Input/default.xci) do on a
 * glide computer event, without the UI: the IGC logger starts on
 * takeoff and stops on landing, as the profile's auto logger setting
 * allows (InputEvents::eventAutoLogger()).  Runs on the core main
 * thread.
 *
 * @param gce a GCE_* value (src/Input/InputQueue.hpp)
 */
void
CoreProcessGlideComputerEvent(unsigned gce) noexcept;

/**
 * Stop everything CoreStartup() started, in the order documented in
 * doc/architecture.rst (network, threads, storage, components).
 */
void
CoreShutdown() noexcept;
