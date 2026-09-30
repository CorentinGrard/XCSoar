// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Start the headless core, optionally replay a flight through it, and
 * shut it down again.  Mirrors the process-wide setup of Main() in
 * src/XCSoar.cpp; the screen is the non-interactive VFB display, whose
 * event loop drives the replay and device timers.
 *
 * Usage: CoreSmoke DATA_DIR [FLIGHT.igc|FLIGHT.nmea]
 */

#include "CoreStartup.hpp"
#include "CoreListener.hpp"
#include "CoreEventLoop.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "Blackboard/DeviceBlackboard.hpp"
#include "Replay/Replay.hpp"
#include "Interface.hpp"
#include "LocalPath.hpp"
#include "Language/Language.hpp"
#include "Language/LanguageGlue.hpp"
#include "Operation/Operation.hpp"
#include "ui/event/Globals.hpp"
#include "ui/event/Queue.hpp"
#include "ui/event/Timer.hpp"
#include "io/async/GlobalAsioThread.hpp"
#include "io/async/AsioThread.hpp"
#include "net/http/Init.hpp"
#include "Audio/GlobalPCMMixer.hpp"
#include "Audio/GlobalPCMResourcePlayer.hpp"
#include "Audio/GlobalVolumeController.hpp"
#include "system/Path.hpp"
#include "util/PrintException.hxx"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>

using std::chrono_literals::operator""s;

/* replay this many times faster than real time */
static constexpr double REPLAY_TIME_SCALE = 200;

/* give up after this long, even if the replay has not finished */
static constexpr auto TIMEOUT = 120s;

class CountingListener final : public CoreListener {
public:
  std::atomic<unsigned> gps{0}, calculated{0}, events{0}, messages{0};

  void OnGPSUpdate() noexcept override {
    ++gps;
  }

  void OnCalculatedUpdate() noexcept override {
    ++calculated;
  }

  void OnGlideComputerEvent(unsigned gce) noexcept override {
    ++events;
    printf("  event GCE %u\n", gce);
  }

  void OnMessage(const char *text, const char *data) noexcept override {
    ++messages;
    printf("  message: %s %s\n", text, data != nullptr ? data : "");
  }
};

static void
RunEventLoop(CoreEventQueue &core_queue, Path flight_path)
{
  auto &replay = *backend_components->replay;
  auto &queue = *UI::event_queue;

  if (flight_path != nullptr) {
    replay.SetTimeScale(REPLAY_TIME_SCALE);
    replay.Start(flight_path, CommonInterface::GetSystemSettings().devices[0]);
  }

  const auto start = std::chrono::steady_clock::now();

  /* poll once per second: quit when the replay is done (or at once
     without a flight, after a short run) */
  UI::Timer check{[&]{
    const auto elapsed = std::chrono::steady_clock::now() - start;
    const bool done = flight_path == nullptr
      ? elapsed >= 2s
      : !replay.IsActive();
    if (done || elapsed >= TIMEOUT)
      queue.Quit();
    else
      check.Schedule(1s);
  }};
  check.Schedule(1s);

  core_queue.Run();
}

static void
PrintState() noexcept
{
  auto &device_blackboard = *backend_components->device_blackboard;
  const std::lock_guard lock{device_blackboard.mutex};
  const auto &basic = device_blackboard.Basic();
  const auto &calculated = device_blackboard.Calculated();

  printf("  location: %s\n",
         basic.location_available ? "valid" : "invalid");
  printf("  flying: %s, flight time: %.0f s\n",
         calculated.flight.flying ? "yes" : "no",
         calculated.flight.flight_time.count());
}

int
main(int argc, char **argv)
try {
  if (argc < 2 || argc > 3) {
    fprintf(stderr, "Usage: %s DATA_DIR [FLIGHT.igc|FLIGHT.nmea]\n",
            argv[0]);
    return EXIT_FAILURE;
  }

  const Path flight_path = argc == 3 ? Path{argv[2]} : Path{nullptr};

  SetSingleDataPath(Path{argv[1]});
  InitialiseDataPath();

  CoreEventQueue core_queue;

  AllowLanguage();
  InitLanguage();

  CountingListener listener;
  SetCoreListener(&listener);

  int ret = EXIT_FAILURE;
  {
    ScopeGlobalAsioThread global_asio_thread;
    const Net::ScopeInit net_init(asio_thread->GetEventLoop());
    ScopeGlobalPCMMixer global_pcm_mixer(asio_thread->GetEventLoop());
    ScopeGlobalPCMResourcePlayer global_pcm_resource_player;
    ScopeGlobalVolumeController global_volume_controller;

    NullOperationEnvironment operation;
    if (CoreStartup(operation)) {
      RunEventLoop(core_queue, flight_path);
      PrintState();
      ret = EXIT_SUCCESS;
    }

    CoreShutdown();
  }

  SetCoreListener(nullptr);

  DisallowLanguage();
  DeinitialiseDataPath();

  printf("CoreSmoke: %s (gps updates: %u, calculated updates: %u, "
         "events: %u, messages: %u)\n",
         ret == EXIT_SUCCESS ? "ok" : "failed",
         listener.gps.load(), listener.calculated.load(),
         listener.events.load(), listener.messages.load());
  return ret;
} catch (...) {
  PrintException(std::current_exception());
  return EXIT_FAILURE;
}
