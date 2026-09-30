// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

/**
 * The UI-free parts of ProcessTimer(): run twice per second on the
 * main thread, by ProcessTimer() or by a host without the XCSoar UI.
 */

/**
 * Settings that change over time: UTC offset, ballast dump, automatic
 * bugs degradation.
 */
void
BackendSettingsTimer() noexcept;

/**
 * Devices (tick, automatic reopen, GPS wait events), replay and
 * simulator housekeeping, and the network clients' timers.
 */
void
BackendDeviceTimer() noexcept;
