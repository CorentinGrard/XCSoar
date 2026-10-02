// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Implementation of the C API (xcsoar_core.h) on top of the headless
 * core in core/host.
 *
 * The core main thread is created by xcs_start().  It owns everything
 * XCSoar normally sets up in main() (data path, language, asio thread,
 * network, the UI event queue) and runs the event loop until
 * xcs_stop().  Commands are injected into that
 * event loop and the caller waits for the result.
 */

#include "xcsoar_core.h"
#include "CoreStartup.hpp"
#include "CoreListener.hpp"
#include "CoreEventLoop.hpp"
#include "CoreReceive.hpp"

#ifdef ANDROID
#include "CoreMap.hpp"
#include "MapSettings.hpp"
#endif
#include "Interface.hpp"
#include "ActionInterface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "MergeThread.hpp"
#include "CalculationThread.hpp"
#include "Replay/Replay.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Engine/Task/TaskManager.hpp"
#include "Engine/Task/AbstractTask.hpp"
#include "Engine/Task/Points/TaskWaypoint.hpp"
#include "Engine/Waypoint/Waypoint.hpp"
#include "Input/InputQueue.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Keys.hpp"
#include "DataComponents.hpp"
#include "Terrain/RasterTerrain.hpp"
#include "Engine/Airspace/Airspaces.hpp"
#include "Engine/Waypoint/Waypoints.hpp"
#include "LocalPath.hpp"
#include "LogFile.hpp"
#include "Language/Language.hpp"
#include "Language/LanguageGlue.hpp"
#include "Operation/Operation.hpp"
#include "Operation/PopupOperationEnvironment.hpp"
#include "ui/event/Globals.hpp"
#include "ui/event/Queue.hpp"
#include "ui/event/Notify.hpp"
#include "ui/event/Timer.hpp"
#include "io/async/GlobalAsioThread.hpp"
#include "io/async/AsioThread.hpp"
#include "net/http/Init.hpp"
#include "Audio/GlobalPCMMixer.hpp"
#include "Audio/GlobalPCMResourcePlayer.hpp"
#include "Audio/GlobalVolumeController.hpp"
#include "system/Path.hpp"
#include "thread/Debug.hpp"
#include "util/UTF8.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <atomic>
#include <cmath>
#include <chrono>
#include <cstring>
#include <future>
#include <iterator>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

/* the snapshot layout is part of the ABI: Kotlin reads it by offset */
static_assert(offsetof(xcs_flight_snapshot, sequence) == 8);
static_assert(offsetof(xcs_flight_snapshot, time_utc) == 24);
static_assert(offsetof(xcs_flight_snapshot, next_name) == 216);
static_assert(sizeof(xcs_flight_snapshot) == 280);

#ifdef ANDROID
/* xcs_map_orientation is XCSoar's MapOrientation */
static_assert(XCS_MAP_TRACK_UP == unsigned(MapOrientation::TRACK_UP));
static_assert(XCS_MAP_NORTH_UP == unsigned(MapOrientation::NORTH_UP));
static_assert(XCS_MAP_TARGET_UP == unsigned(MapOrientation::TARGET_UP));
static_assert(XCS_MAP_HEADING_UP == unsigned(MapOrientation::HEADING_UP));
static_assert(XCS_MAP_WIND_UP == unsigned(MapOrientation::WIND_UP));
#endif

/* stable public codes for the internal GCE_* values */
static constexpr xcs_gce gce_map[] = {
  XCS_GCE_AIRSPACE_NEAR,          // GCE_AIRSPACE_NEAR
  XCS_GCE_AIRSPACE_ENTER,         // GCE_AIRSPACE_ENTER
  XCS_GCE_AIRSPACE_LEAVE,         // GCE_AIRSPACE_LEAVE
  XCS_GCE_COMMPORT_RESTART,       // GCE_COMMPORT_RESTART
  XCS_GCE_FLARM_NOTRAFFIC,        // GCE_FLARM_NOTRAFFIC
  XCS_GCE_FLARM_TRAFFIC,          // GCE_FLARM_TRAFFIC
  XCS_GCE_FLARM_NEWTRAFFIC,       // GCE_FLARM_NEWTRAFFIC
  XCS_GCE_FLIGHTMODE_CLIMB,       // GCE_FLIGHTMODE_CLIMB
  XCS_GCE_FLIGHTMODE_CRUISE,      // GCE_FLIGHTMODE_CRUISE
  XCS_GCE_FLIGHTMODE_FINALGLIDE,  // GCE_FLIGHTMODE_FINALGLIDE
  XCS_GCE_FINALGLIDE_TERRAIN,     // GCE_FLIGHTMODE_FINALGLIDE_TERRAIN
  XCS_GCE_FINALGLIDE_ABOVE,       // GCE_FLIGHTMODE_FINALGLIDE_ABOVE
  XCS_GCE_FINALGLIDE_BELOW,       // GCE_FLIGHTMODE_FINALGLIDE_BELOW
  XCS_GCE_GPS_CONNECTION_WAIT,    // GCE_GPS_CONNECTION_WAIT
  XCS_GCE_GPS_FIX_WAIT,           // GCE_GPS_FIX_WAIT
  XCS_GCE_HEIGHT_MAX,             // GCE_HEIGHT_MAX
  XCS_GCE_LANDING,                // GCE_LANDING
  XCS_GCE_OTHER,                  // GCE_STARTUP_REAL
  XCS_GCE_OTHER,                  // GCE_STARTUP_SIMULATOR
  XCS_GCE_TAKEOFF,                // GCE_TAKEOFF
  XCS_GCE_TASK_NEXTWAYPOINT,      // GCE_TASK_NEXTWAYPOINT
  XCS_GCE_TASK_START,             // GCE_TASK_START
  XCS_GCE_TASK_FINISH,            // GCE_TASK_FINISH
  XCS_GCE_TEAM_POS_REACHED,       // GCE_TEAM_POS_REACHED
  XCS_GCE_ARM_READY,              // GCE_ARM_READY
  XCS_GCE_POLAR_CHANGED,          // GCE_POLAR_CHANGED
  XCS_GCE_ALTERNATE_CHANGED,      // GCE_ALTERNATE_CHANGED
  XCS_GCE_LANDABLE_UNREACHABLE,   // GCE_LANDABLE_UNREACHABLE
};
static_assert(std::size(gce_map) == GCE_COUNT,
              "update gce_map when Input/InputQueue.hpp changes");

struct PendingEvent {
  xcs_event_type type;
  uint32_t code;
  std::string text, detail;
};

struct xcs_core final : CoreListener {
  const std::string data_path, profile;
  const uint32_t flags;
  const xcs_snapshot_callback on_snapshot;
  const xcs_event_callback on_event;
  void *const callback_ctx;

  std::thread thread;
  std::atomic<std::thread::id> thread_id{};
  bool started = false;

  std::mutex snapshot_mutex;
  xcs_flight_snapshot latest{};
  uint64_t sequence = 0;

  /* events from backend threads, delivered on the main thread */
  std::mutex event_mutex;
  std::vector<PendingEvent> pending_events;
  std::unique_ptr<UI::Notify> event_notify;

  /* set while xcs_replay_run() drives the calculation itself */
  bool replay_run_active = false;
  double replay_run_interval = 0;
  double replay_run_last_time = -1;

  std::unique_ptr<UI::Timer> replay_watch;

  explicit xcs_core(const xcs_config &config) noexcept
    :data_path(config.data_path),
     profile(config.profile != nullptr ? config.profile : ""),
     flags(config.flags),
     on_snapshot(config.on_snapshot),
     on_event(config.on_event),
     callback_ctx(config.callback_ctx) {}

  bool IsMainThread() const noexcept {
    return std::this_thread::get_id() == thread_id.load();
  }

  void Run(std::promise<xcs_status> &started_promise) noexcept;

  void PublishSnapshot() noexcept;
  void QueueEvent(PendingEvent &&event) noexcept;
  void DeliverEvents() noexcept;

  /* virtual methods from CoreListener */
  void OnGPSUpdate() noexcept override {
    if (!replay_run_active)
      PublishSnapshot();
  }

  void OnCalculatedUpdate() noexcept override {
    if (!replay_run_active)
      PublishSnapshot();
  }

  void OnGlideComputerEvent(unsigned gce) noexcept override {
    QueueEvent({XCS_EVENT_GLIDE_COMPUTER,
                gce < GCE_COUNT ? uint32_t(gce_map[gce]) : uint32_t(XCS_GCE_OTHER),
                {}, {}});
  }

  void OnMessage(const char *text, const char *data) noexcept override {
    QueueEvent({XCS_EVENT_MESSAGE, 0,
                text != nullptr ? text : "",
                data != nullptr ? data : ""});
  }
};

/* XCSoar uses process-wide globals: only one core at a time */
static std::mutex instance_mutex;
static xcs_core *instance;

static void
FillSnapshot(xcs_flight_snapshot &s) noexcept
{
  const auto &basic = CommonInterface::Basic();
  const auto &calculated = CommonInterface::Calculated();
  const auto &settings = CommonInterface::GetComputerSettings();

  uint32_t valid = 0, flags = 0;

  if (basic.time_available && basic.date_time_utc.IsPlausible()) {
    valid |= XCS_VALID_TIME;
    s.time_utc = std::chrono::duration<double>(
      basic.date_time_utc.ToTimePoint().time_since_epoch()).count();
  }

  if (basic.location_available) {
    valid |= XCS_VALID_LOCATION;
    s.latitude = basic.location.latitude.Degrees();
    s.longitude = basic.location.longitude.Degrees();
  }

  if (basic.track_available) {
    valid |= XCS_VALID_TRACK;
    s.track = basic.track.Degrees();
  }

  if (basic.ground_speed_available) {
    valid |= XCS_VALID_GROUND_SPEED;
    s.ground_speed = basic.ground_speed;
  }

  if (basic.airspeed_available) {
    valid |= XCS_VALID_AIRSPEED;
    s.true_airspeed = basic.true_airspeed;
    s.indicated_airspeed = basic.indicated_airspeed;
  }

  if (basic.gps_altitude_available) {
    valid |= XCS_VALID_GPS_ALTITUDE;
    s.gps_altitude = basic.gps_altitude;
  }

  if (basic.baro_altitude_available) {
    valid |= XCS_VALID_BARO_ALTITUDE;
    s.baro_altitude = basic.baro_altitude;
  }

  if (basic.NavAltitudeAvailable()) {
    valid |= XCS_VALID_NAV_ALTITUDE;
    s.nav_altitude = basic.nav_altitude;
  }

  if (calculated.terrain_valid) {
    valid |= XCS_VALID_TERRAIN;
    s.terrain_altitude = calculated.terrain_altitude;
    s.altitude_agl = calculated.altitude_agl;
  }

  if (basic.brutto_vario_available) {
    valid |= XCS_VALID_VARIO;
    s.vario = basic.brutto_vario;
    s.average_vario = calculated.average;
  }

  if (basic.netto_vario_available) {
    valid |= XCS_VALID_NETTO_VARIO;
    s.netto_vario = basic.netto_vario;
  }

  if (calculated.wind_available) {
    valid |= XCS_VALID_WIND;
    s.wind_speed = calculated.wind.norm;
    s.wind_bearing = calculated.wind.bearing.Degrees();
  }

  s.mac_cready = settings.polar.glide_polar_task.GetMC();

  const auto &task_stats = calculated.task_stats;
  if (task_stats.task_valid) {
    valid |= XCS_VALID_TASK;

    const auto &leg = task_stats.current_leg;
    if (leg.vector_remaining.IsValid() && leg.solution_remaining.IsOk()) {
      valid |= XCS_VALID_NEXT_WAYPOINT;
      s.next_distance = leg.vector_remaining.distance;
      s.next_bearing = leg.vector_remaining.bearing.Degrees();
      s.next_altitude_difference = leg.solution_remaining.altitude_difference;
    }

    const auto &total = task_stats.total;
    if (total.solution_remaining.IsOk()) {
      valid |= XCS_VALID_FINAL_GLIDE;
      s.task_remaining_distance = total.remaining.GetDistance();
      s.final_glide_altitude_difference =
        total.solution_remaining.altitude_difference;
    }

    if (task_stats.flight_mode_final_glide)
      flags |= XCS_FLAG_FINAL_GLIDE;
  }

  s.next_name[0] = '\0';
  if ((valid & XCS_VALID_NEXT_WAYPOINT) &&
      backend_components->protected_task_manager) {
    const ProtectedTaskManager::Lease lease{*backend_components->protected_task_manager};
    const auto *task = lease->GetActiveTask();
    const auto *tp = task != nullptr ? task->GetActiveTaskPoint() : nullptr;
    if (tp != nullptr)
      CopyTruncateStringUTF8(std::span{s.next_name},
                             tp->GetWaypoint().name.c_str(),
                             sizeof(s.next_name) - 1);
  }

  if (basic.gps.real)
    flags |= XCS_FLAG_GPS_REAL;
  if (basic.gps.replay)
    flags |= XCS_FLAG_REPLAY;
  if (calculated.flight.flying)
    flags |= XCS_FLAG_FLYING;
  if (calculated.circling)
    flags |= XCS_FLAG_CIRCLING;

  s.flight_time = calculated.flight.flight_time.count();
  s.valid = valid;
  s.flags = flags;
}

void
xcs_core::PublishSnapshot() noexcept
{
  xcs_flight_snapshot s{};
  s.struct_size = sizeof(s);
  s.api_version = XCS_API_VERSION;
  FillSnapshot(s);

  {
    const std::lock_guard lock{snapshot_mutex};
    s.sequence = ++sequence;
    latest = s;
  }

  if (on_snapshot != nullptr)
    on_snapshot(callback_ctx, &s);

#ifdef ANDROID
  CoreMap::Invalidate();
#endif
}

void
xcs_core::QueueEvent(PendingEvent &&event) noexcept
{
  {
    const std::lock_guard lock{event_mutex};
    pending_events.push_back(std::move(event));
  }

  if (IsMainThread())
    DeliverEvents();
  else if (event_notify)
    event_notify->SendNotification();
}

void
xcs_core::DeliverEvents() noexcept
{
  std::vector<PendingEvent> events;
  {
    const std::lock_guard lock{event_mutex};
    events.swap(pending_events);
  }

  if (on_event == nullptr)
    return;

  for (const auto &e : events) {
    const xcs_event event{
      sizeof(xcs_event), uint32_t(e.type), e.code,
      e.text.empty() ? nullptr : e.text.c_str(),
      e.detail.empty() ? nullptr : e.detail.c_str(),
    };
    on_event(callback_ctx, &event);
  }
}

void
xcs_core::Run(std::promise<xcs_status> &started_promise) noexcept
{
  thread_id = std::this_thread::get_id();
  InitThreadDebug();

  bool promise_set = false;
  auto report = [&](xcs_status status) {
    if (!promise_set) {
      promise_set = true;
      started_promise.set_value(status);
    }
  };

  try {
    SetSingleDataPath(Path{data_path.c_str()});
    InitialiseDataPath();

    CoreEventQueue core_queue;

    AllowLanguage();
    InitLanguage();

    {
      ScopeGlobalAsioThread global_asio_thread;
      const Net::ScopeInit net_init(asio_thread->GetEventLoop());
      ScopeGlobalPCMMixer global_pcm_mixer(asio_thread->GetEventLoop());
      ScopeGlobalPCMResourcePlayer global_pcm_resource_player;
      ScopeGlobalVolumeController global_volume_controller;

      if (!profile.empty())
        Profile::SetFiles(Path{profile.c_str()});

      event_notify = std::make_unique<UI::Notify>([this]{ DeliverEvents(); });
      SetCoreListener(this);

      NullOperationEnvironment operation;
      if (CoreStartup(operation, !(flags & XCS_CONFIG_NO_DEVICES))) {
        PublishSnapshot();
        report(XCS_OK);

        core_queue.Run();
      } else
        report(XCS_ERROR_FAILED);

      replay_watch.reset();
#ifdef ANDROID
      CoreMap::Deinitialise();
#endif
      CoreShutdown();

      SetCoreListener(nullptr);
      event_notify.reset();
    }

    DisallowLanguage();
  } catch (...) {
    LogError(std::current_exception(), "xcsoar_core");
    report(XCS_ERROR_FAILED);
  }

  DeinitialiseDataPath();
  thread_id = std::thread::id{};
}

/**
 * Run a function on the core main thread and return its result.  Runs
 * it directly when already on the main thread (e.g. from a callback).
 */
template<typename F>
static xcs_status
RunOnMain(xcs_core &core, F &&f) noexcept
{
  if (core.IsMainThread())
    return f();

  if (!core.started || UI::event_queue == nullptr)
    return XCS_ERROR_STATE;

  std::packaged_task<xcs_status()> task{std::forward<F>(f)};
  auto result = task.get_future();

  UI::event_queue->InjectCall([](void *ctx) noexcept {
    (*static_cast<std::packaged_task<xcs_status()> *>(ctx))();
  }, &task);

  return result.get();
}

/* API */

uint32_t
xcs_api_version(void)
{
  return XCS_API_VERSION;
}

xcs_status
xcs_create(const xcs_config *config, xcs_core **core_r)
{
  if (config == nullptr || core_r == nullptr ||
      config->struct_size < sizeof(xcs_config) ||
      config->api_version != XCS_API_VERSION ||
      config->data_path == nullptr || *config->data_path == '\0')
    return XCS_ERROR_INVALID_ARGUMENT;

  const std::lock_guard lock{instance_mutex};
  if (instance != nullptr)
    return XCS_ERROR_BUSY;

  instance = new xcs_core(*config);
  *core_r = instance;
  return XCS_OK;
}

xcs_status
xcs_start(xcs_core *core)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;
  if (core->started || core->IsMainThread())
    return XCS_ERROR_STATE;

  std::promise<xcs_status> started_promise;
  auto result = started_promise.get_future();
  core->thread = std::thread([core, &started_promise]{
    core->Run(started_promise);
  });

  const xcs_status status = result.get();
  if (status != XCS_OK) {
    core->thread.join();
    return status;
  }

  core->started = true;
  return XCS_OK;
}

xcs_status
xcs_stop(xcs_core *core)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;
  if (!core->started || core->IsMainThread())
    return XCS_ERROR_STATE;

  RunOnMain(*core, []{
    UI::event_queue->Quit();
    return XCS_OK;
  });

  core->thread.join();
  core->started = false;
  return XCS_OK;
}

void
xcs_destroy(xcs_core *core)
{
  if (core == nullptr)
    return;

  if (core->started)
    xcs_stop(core);

  const std::lock_guard lock{instance_mutex};
  if (instance == core)
    instance = nullptr;
  delete core;
}

xcs_status
xcs_set_mac_cready(xcs_core *core, double mac_cready)
{
  if (core == nullptr || !(mac_cready >= 0 && mac_cready <= 5))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [mac_cready]{
    ActionInterface::SetManualMacCready(mac_cready, true);
    return XCS_OK;
  });
}

xcs_status
xcs_get_snapshot(xcs_core *core, xcs_flight_snapshot *snapshot)
{
  if (core == nullptr || snapshot == nullptr ||
      snapshot->struct_size < sizeof(xcs_flight_snapshot))
    return XCS_ERROR_INVALID_ARGUMENT;
  if (!core->started)
    return XCS_ERROR_STATE;

  const std::lock_guard lock{core->snapshot_mutex};
  *snapshot = core->latest;
  return XCS_OK;
}

/** The profile key holding the files of an xcs_data_file. */
static constexpr std::string_view
DataFileKey(uint32_t kind) noexcept
{
  switch (kind) {
  case XCS_DATA_MAP:
    return ProfileKeys::MapFile;
  case XCS_DATA_AIRSPACE:
    return ProfileKeys::AirspaceFileList;
  case XCS_DATA_WAYPOINTS:
    return ProfileKeys::WaypointFileList;
  }

  return {};
}

xcs_status
xcs_set_data_file(xcs_core *core, uint32_t kind, const char *path)
{
  const auto key = DataFileKey(kind);
  if (core == nullptr || key.empty() ||
      (path != nullptr && !ValidateUTF8(path)))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [kind, key, path]{
    /* a single file is also a valid "list" for the list keys */
    Profile::SetPath(key, Path{path != nullptr ? path : ""});
    Profile::Save();

    PopupOperationEnvironment operation;
    CoreReloadDataFiles(kind == XCS_DATA_MAP, kind == XCS_DATA_WAYPOINTS,
                        kind == XCS_DATA_AIRSPACE, operation);
#ifdef ANDROID
    CoreMap::OnDataChanged();
#endif
    return XCS_OK;
  });
}

static boost::json::array
ConfiguredFiles(std::string_view key) noexcept
{
  boost::json::array files;
  for (const auto &path : Profile::GetMultiplePaths(key, nullptr))
    files.emplace_back(path.c_str());
  return files;
}

xcs_status
xcs_get_data_status(xcs_core *core, char *buffer, size_t size,
                    size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [buffer, size, length_r]{
    const auto &data = *data_components;
    const boost::json::value status = boost::json::object{
      {"map", {
          {"files", ConfiguredFiles(ProfileKeys::MapFile)},
          {"terrain", data.terrain != nullptr},
        }},
      {"airspace", {
          {"files", ConfiguredFiles(ProfileKeys::AirspaceFileList)},
          {"count", data.airspaces->GetSize()},
        }},
      {"waypoints", {
          {"files", ConfiguredFiles(ProfileKeys::WaypointFileList)},
          {"count", data.waypoints->size()},
        }},
    };

    StringOutputStream os;
    Json::Serialize(os, status);
    const auto &json = os.GetValue();
    *length_r = json.size();
    if (json.size() >= size)
      return XCS_ERROR_INVALID_ARGUMENT;

    std::memcpy(buffer, json.c_str(), json.size() + 1);
    return XCS_OK;
  });
}

xcs_status
xcs_map_attach(xcs_core *core, void *native_window, uint32_t width,
               uint32_t height, uint32_t dpi)
{
  if (core == nullptr || native_window == nullptr || width == 0 ||
      height == 0 || width >= 0x8000 || height >= 0x8000 || dpi == 0)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [=]{
    return CoreMap::Attach(static_cast<ANativeWindow *>(native_window),
                           width, height, dpi)
      ? XCS_OK
      : XCS_ERROR_FAILED;
  });
#else
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_map_detach(xcs_core *core)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, []{
    CoreMap::Detach();
    return XCS_OK;
  });
#else
  return XCS_OK;
#endif
}

xcs_status
xcs_map_set_aircraft_position(xcs_core *core, int32_t x, int32_t y)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [x, y]{
    CoreMap::SetAircraftPosition(x, y);
    return XCS_OK;
  });
#else
  (void)x;
  (void)y;
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_map_zoom(xcs_core *core, int32_t steps)
{
  if (core == nullptr || steps < -20 || steps > 20)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [steps]{
    CoreMap::Zoom(steps);
    return XCS_OK;
  });
#else
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_map_pan(xcs_core *core, float dx, float dy)
{
  if (core == nullptr || !std::isfinite(dx) || !std::isfinite(dy))
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [dx, dy]{
    CoreMap::Pan(dx, dy);
    return XCS_OK;
  });
#else
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_map_scale(xcs_core *core, float factor)
{
  if (core == nullptr || !(factor > 0.1f && factor < 10.f))
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [factor]{
    CoreMap::Scale(factor);
    return XCS_OK;
  });
#else
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_map_follow(xcs_core *core)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, []{
    CoreMap::Follow();
    return XCS_OK;
  });
#else
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_map_set_orientation(xcs_core *core, uint32_t orientation)
{
  if (core == nullptr || orientation > XCS_MAP_WIND_UP)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [orientation]{
    CoreMap::SetOrientation(orientation);
    return XCS_OK;
  });
#else
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_map_get_orientation(xcs_core *core, uint32_t *orientation_r)
{
  if (core == nullptr || orientation_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [orientation_r]{
    *orientation_r = CoreMap::GetOrientation();
    return XCS_OK;
  });
#else
  return XCS_ERROR_FAILED;
#endif
}

static xcs_status
StartReplay(const char *path) noexcept
try {
  backend_components->replay->Start(Path{path},
                                    CommonInterface::GetSystemSettings().devices[0]);
  return XCS_OK;
} catch (...) {
  LogError(std::current_exception(), "Replay failed");
  return XCS_ERROR_FAILED;
}

xcs_status
xcs_replay_start(xcs_core *core, const char *path, double time_scale)
{
  if (core == nullptr || path == nullptr || !(time_scale > 0))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [core, path, time_scale]{
    auto &replay = *backend_components->replay;
    replay.SetTimeScale(time_scale);
    if (const auto status = StartReplay(path); status != XCS_OK)
      return status;

    /* Replay has no "finished" callback; check once per second */
    core->replay_watch = std::make_unique<UI::Timer>([core]{
      if (backend_components->replay->IsActive()) {
        core->replay_watch->Schedule(std::chrono::seconds(1));
        return;
      }

      core->QueueEvent({XCS_EVENT_REPLAY_FINISHED, 0, {}, {}});
    });
    core->replay_watch->Schedule(std::chrono::seconds(1));
    return XCS_OK;
  });
}

xcs_status
xcs_replay_stop(xcs_core *core)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [core]{
    core->replay_watch.reset();
    backend_components->replay->Stop();
    return XCS_OK;
  });
}

xcs_status
xcs_replay_run(xcs_core *core, const char *path, double snapshot_interval,
               uint32_t *fixes_r)
{
  if (core == nullptr || path == nullptr || !(snapshot_interval >= 0))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [core, path, snapshot_interval, fixes_r]{
    auto &bc = *backend_components;

    core->replay_watch.reset();
    bc.replay->Stop();
    if (const auto status = StartReplay(path); status != XCS_OK)
      return status;

    bc.merge_thread->Suspend();
    bc.calculation_thread->Suspend();

    core->replay_run_active = true;
    core->replay_run_interval = snapshot_interval;
    core->replay_run_last_time = -1;

    PopupOperationEnvironment env;
    const unsigned fixes =
      bc.replay->ProcessAllFixes(*bc.merge_thread, *bc.calculation_thread,
                                 [core, &env]{
      /* what the event loop would do after each merge/calculation */
      CoreReceiveSensorData(env);
      CoreReceiveCalculatedData();

      const auto &basic = CommonInterface::Basic();
      if (!basic.time_available)
        return;

      const double t = basic.time.ToDuration().count();
      if (core->replay_run_last_time < 0 ||
          t - core->replay_run_last_time >= core->replay_run_interval ||
          t < core->replay_run_last_time) {
        core->replay_run_last_time = t;
        core->PublishSnapshot();
      }
    });

    /* the final state, before the threads resume and start expiring
       the (now stale) replay data */
    core->PublishSnapshot();
    core->QueueEvent({XCS_EVENT_REPLAY_FINISHED, 0, {}, {}});

    core->replay_run_active = false;
    bc.replay->Stop();

    bc.calculation_thread->Resume();
    bc.merge_thread->Resume();

    if (fixes_r != nullptr)
      *fixes_r = fixes;
    return fixes > 0 ? XCS_OK : XCS_ERROR_FAILED;
  });
}
