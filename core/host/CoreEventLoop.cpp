// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreEventLoop.hpp"
#include "ui/event/Globals.hpp"
#include "ui/event/Queue.hpp"
#include "ui/event/Timer.hpp"
#include "ui/event/shared/Event.hpp"

#include <cassert>

CoreEventQueue::CoreEventQueue() noexcept
{
#ifdef ANDROID
  assert(UI::event_queue == nullptr);
  UI::event_queue = &queue;
#endif
}

CoreEventQueue::~CoreEventQueue() noexcept
{
#ifdef ANDROID
  UI::event_queue = nullptr;
#endif
}

void
CoreEventQueue::Run() noexcept
{
  auto &q = *UI::event_queue;

  /* like UI::EventLoop, minus the TopWindow: there is no window, so
     only callbacks (UI::Notify, injected commands) and timers matter */
  UI::Event event;
  while (!q.IsQuit() && q.Wait(event)) {
    switch (event.type) {
    case UI::Event::CALLBACK:
      event.callback(event.ptr);
      break;

#ifdef ANDROID
    case UI::Event::TIMER:
      /* the poll() queue invokes timers itself */
      static_cast<UI::Timer *>(event.ptr)->Invoke();
      break;
#endif

    default:
      break;
    }
  }
}
