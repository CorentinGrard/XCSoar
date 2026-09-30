// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#pragma once

#ifdef ANDROID
#include "ui/event/Queue.hpp"
#else
#include "ui/window/Init.hpp"
#endif

/**
 * The UI event queue of the core main thread, without any window.
 *
 * On Android this is XCSoar's plain C++ queue (normally fed by the
 * Java NativeView); elsewhere the non-interactive VFB display with the
 * poll() based queue.  Timers (UI::Timer) and notifications
 * (UI::Notify) work as in the normal program.
 */
class CoreEventQueue {
#ifdef ANDROID
  UI::EventQueue queue;
#else
  ScreenGlobalInit screen_init;
#endif

public:
  CoreEventQueue() noexcept;
  ~CoreEventQueue() noexcept;

  CoreEventQueue(const CoreEventQueue &) = delete;
  CoreEventQueue &operator=(const CoreEventQueue &) = delete;

  /**
   * Dispatch events (callbacks and timers) until
   * UI::EventQueue::Quit() is called.
   */
  void Run() noexcept;
};
