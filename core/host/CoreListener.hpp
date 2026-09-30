// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

/**
 * Receives what the backend would normally hand to the user
 * interface.  The headless seams in core/host (Protection.cpp,
 * Seams.cpp) forward to the registered listener.
 *
 * OnGPSUpdate() and OnCalculatedUpdate() are called on the core main
 * thread after the InterfaceBlackboard (CommonInterface) has been
 * updated, so they may read it.  The other methods may be called from
 * any backend thread.  Implementations must be thread-safe and return
 * quickly.
 */
class CoreListener {
public:
  /** New sensor data was merged (from TriggerVarioUpdate(), which
      MergeThread calls after every merge). */
  virtual void OnGPSUpdate() noexcept {}

  /** New calculation results (from TriggerCalculatedUpdate()). */
  virtual void OnCalculatedUpdate() noexcept {}

  /** A glide computer event, one of the GCE_* values from
      Input/InputQueue.hpp (take-off, landing, circling, ...). */
  virtual void OnGlideComputerEvent([[maybe_unused]] unsigned gce) noexcept {}

  /** A status message (from Message::AddMessage()).  @param data may be
      nullptr */
  virtual void OnMessage([[maybe_unused]] const char *text,
                         [[maybe_unused]] const char *data) noexcept {}
};

/**
 * Register the listener; nullptr unregisters it.  Call only while the
 * core is stopped.
 */
void
SetCoreListener(CoreListener *listener) noexcept;

CoreListener *
GetCoreListener() noexcept;
