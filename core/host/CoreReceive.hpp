// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

class OperationEnvironment;

/**
 * Headless counterpart of UIReceiveSensorData() (src/UIReceiveBlackboard.cpp):
 * copy the merged sensor data into the InterfaceBlackboard, notify its
 * listeners and apply settings received from devices.  Runs on the
 * core main thread.
 */
void
CoreReceiveSensorData(OperationEnvironment &env) noexcept;

/**
 * Headless counterpart of UIReceiveCalculatedData(): copy the
 * calculation results into the InterfaceBlackboard, notify its
 * listeners (e.g. GlideComputerEvents), pass them to the devices and
 * check for task events.  Runs on the core main thread.
 */
void
CoreReceiveCalculatedData() noexcept;

/**
 * Create the notifications that hand merge/calculation results from
 * the backend threads to the core main thread.  Call on the core main
 * thread, after the UI event queue exists and before the backend
 * threads start.
 */
void
CoreInitNotify() noexcept;

void
CoreDeinitNotify() noexcept;

/** Thread-safe; repeated calls before the main thread runs coalesce. */
void
CoreSendGPSNotify() noexcept;

/** Thread-safe; repeated calls before the main thread runs coalesce. */
void
CoreSendCalculatedNotify() noexcept;
