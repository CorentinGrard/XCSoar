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
#include "Engine/Task/Stats/TaskStats.hpp"
#include "Engine/Util/Gradient.hpp"
#include "Computer/STF.hpp"
#include "Engine/Route/ReachResult.hpp"
#include "Computer/GlideComputer.hpp"
#include "Computer/WaypointReach.hpp"
#include "Waypoint/WaypointListBuilder.hpp"
#include "Waypoint/WaypointList.hpp"
#include "Waypoint/WaypointFilter.hpp"
#include "Formatter/AirspaceFormatter.hpp"
#include "Engine/Airspace/AbstractAirspace.hpp"
#include "Engine/Airspace/AirspaceWarning.hpp"
#include "Engine/Airspace/AirspaceWarningManager.hpp"
#include "Airspace/ProtectedAirspaceWarningManager.hpp"
#include "util/HexFormat.hxx"
#include "io/FileLineReader.hpp"
#include "Repository/FileType.hpp"
#include "Repository/Parser.hpp"
#include "Repository/FileRepository.hpp"
#include "Protection.hpp"
#include "CoreStartup.hpp"
#include "CoreListener.hpp"
#include "CoreEventLoop.hpp"
#include "CoreReceive.hpp"
#include "CoreTask.hpp"
#include "CoreAnalysis.hpp"
#include "CorePlanes.hpp"
#include "CoreInfoBoxes.hpp"
#include "CoreWeGlide.hpp"
#include "util/Exception.hxx"
#include "CoreUnits.hpp"

#ifdef ANDROID
#include "CoreMap.hpp"
#endif
#include "MapSettings.hpp"
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
#include "Profile/AirspaceConfig.hpp"
#include "Profile/Current.hpp"
#include "Renderer/AirspaceRendererSettings.hpp"
#include "Engine/Airspace/Airspace.hpp"
#include "Audio/VarioGlue.hpp"
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
#include "Operation/MessageOperationEnvironment.hpp"
#include "Device/MultipleDevices.hpp"
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
#include "Math/Util.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <array>
#include <atomic>
#include <cmath>
#include <chrono>
#include <cstdio>
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
static_assert(offsetof(xcs_flight_snapshot, speed_to_fly) == 280);
static_assert(offsetof(xcs_flight_snapshot, last_thermal_duration) == 360);
static_assert(offsetof(xcs_flight_snapshot, ballast) == 368);
static_assert(offsetof(xcs_flight_snapshot, qnh) == 400);
static_assert(sizeof(xcs_flight_snapshot) == 416);

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
  /** The GCE_* value of a glide computer event, else GCE_COUNT. */
  unsigned gce = GCE_COUNT;
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

  /* sensors merge many times per second (the phone's barometer,
     accelerometer and gyroscope each), and every merge updates GPS and
     calculated data: snapshots go out at most this often, the latest
     one at the end of each interval */
  static constexpr auto SNAPSHOT_INTERVAL = std::chrono::milliseconds{100};
  std::chrono::steady_clock::time_point last_snapshot;
  std::unique_ptr<UI::Timer> snapshot_timer;

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
      SchedulePublish();
  }

  void OnCalculatedUpdate() noexcept override {
    if (!replay_run_active)
      SchedulePublish();
  }

  /** New data: publish now, or at the end of this interval. */
  void SchedulePublish() noexcept {
    /* stopping: nobody reads them any more */
    if (!snapshot_timer)
      return;

    const auto elapsed = std::chrono::steady_clock::now() - last_snapshot;
    if (elapsed >= SNAPSHOT_INTERVAL) {
      snapshot_timer->Cancel();
      PublishSnapshot();
    } else if (!snapshot_timer->IsPending())
      snapshot_timer->Schedule(
        std::chrono::duration_cast<std::chrono::steady_clock::duration>(
          SNAPSHOT_INTERVAL - elapsed));
  }

  void OnGlideComputerEvent(unsigned gce) noexcept override {
    QueueEvent({XCS_EVENT_GLIDE_COMPUTER,
                gce < GCE_COUNT ? uint32_t(gce_map[gce]) : uint32_t(XCS_GCE_OTHER),
                {}, {}, gce});
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

  const GlidePolar &polar = settings.polar.glide_polar_task;
  s.mac_cready = polar.GetMC();
  s.ballast = polar.GetBallastLitres();
  s.max_ballast = polar.IsBallastable() ? polar.GetMaxBallast() : 0;
  s.bugs = settings.polar.bugs;
  s.wing_loading = polar.GetWingLoading();

  s.qnh = settings.pressure.GetHectoPascal();
  if (settings.pressure_available)
    valid |= XCS_VALID_QNH;

  if (basic.static_pressure_available) {
    valid |= XCS_VALID_STATIC_PRESSURE;
    s.static_pressure = basic.static_pressure.GetHectoPascal();
  }

  /* the conditions of the matching InfoBoxes (src/InfoBoxes/Content) */
  if (const auto stf = GetSTFSpeed(basic, calculated)) {
    valid |= XCS_VALID_SPEED_TO_FLY;
    s.speed_to_fly = *stf;
  }

  if (GradientValid(calculated.gr)) {
    valid |= XCS_VALID_LD;
    s.ld = calculated.gr;
  }

  if (calculated.current_thermal.IsDefined()) {
    valid |= XCS_VALID_CURRENT_THERMAL;
    s.current_thermal_lift = calculated.current_thermal.lift_rate;
    s.current_thermal_gain = calculated.current_thermal.gain;
    s.current_thermal_duration = calculated.current_thermal.duration.count();
  }

  if (calculated.last_thermal.IsDefined()) {
    valid |= XCS_VALID_LAST_THERMAL;
    s.last_thermal_lift = calculated.last_thermal.lift_rate;
    s.last_thermal_gain = calculated.last_thermal.gain;
    s.last_thermal_duration = calculated.last_thermal.duration.count();
  }

  const auto &task_stats = calculated.task_stats;
  if (task_stats.task_valid) {
    valid |= XCS_VALID_TASK;

    const auto &leg = task_stats.current_leg;

    if (leg.gradient <= 0) {
      valid |= XCS_VALID_LD_REQUIRED;
      s.ld_required = 0;
    } else if (GradientValid(leg.gradient)) {
      valid |= XCS_VALID_LD_REQUIRED;
      s.ld_required = leg.gradient;
    }

    if (leg.IsAchievable()) {
      valid |= XCS_VALID_NEXT_TIME;
      s.next_time_remaining = leg.time_remaining_now.count();
    }

    if (task_stats.total.travelled.IsDefined()) {
      valid |= XCS_VALID_TASK_SPEED;
      s.task_speed = task_stats.total.travelled.GetSpeed();
    }
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
  last_snapshot = std::chrono::steady_clock::now();

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

  /* XCSoar's own reactions first (the auto logger), like its input
     events, on this thread as there */
  for (const auto &e : events)
    if (e.gce < GCE_COUNT)
      CoreProcessGlideComputerEvent(e.gce);

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
      snapshot_timer = std::make_unique<UI::Timer>([this]{ PublishSnapshot(); });
      SetCoreListener(this);

      NullOperationEnvironment operation;
      if (CoreStartup(operation, !(flags & XCS_CONFIG_NO_DEVICES))) {
        PublishSnapshot();
        report(XCS_OK);

        core_queue.Run();
      } else
        report(XCS_ERROR_FAILED);

      replay_watch.reset();
      snapshot_timer.reset();
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
xcs_set_ballast(xcs_core *core, double litres)
{
  if (core == nullptr || !(litres >= 0))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [litres]{
    const GlidePolar &polar =
      CommonInterface::GetComputerSettings().polar.glide_polar_task;
    const double max = polar.IsBallastable() ? polar.GetMaxBallast() : 0;
    if (litres > max)
      return XCS_ERROR_INVALID_ARGUMENT;

    ActionInterface::SetBallastLitres(litres, true);
    return XCS_OK;
  });
}

xcs_status
xcs_set_bugs(xcs_core *core, double bugs)
{
  if (core == nullptr || !(bugs >= 0.5 && bugs <= 1))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [bugs]{
    ActionInterface::SetBugs(bugs, true);
    return XCS_OK;
  });
}

xcs_status
xcs_set_qnh(xcs_core *core, double hpa)
{
  if (core == nullptr || !(hpa >= 850 && hpa <= 1300))
    return XCS_ERROR_INVALID_ARGUMENT;

  /* ActionInterface::SetQNH() without its MainWindow and InfoBox
     calls; the calculation thread gets the settings on the next
     timer tick */
  return RunOnMain(*core, [hpa]{
    const auto qnh = AtmosphericPressure::HectoPascal(hpa);
    auto &settings = CommonInterface::SetComputerSettings();
    settings.pressure = qnh;
    settings.pressure_available.Update(CommonInterface::Basic().clock);

    if (backend_components && backend_components->devices) {
      MessageOperationEnvironment env;
      backend_components->devices->PutQNH(qnh, env);
    }
    return XCS_OK;
  });
}

/**
 * Copy JSON into the caller's buffer (the rules of
 * xcs_get_data_status()).
 */
static xcs_status
CopyJson(const std::string &json, char *buffer, size_t size,
         size_t *length_r) noexcept
{
  *length_r = json.size();
  if (json.size() >= size)
    return XCS_ERROR_INVALID_ARGUMENT;

  std::memcpy(buffer, json.c_str(), json.size() + 1);
  return XCS_OK;
}

xcs_status
xcs_units_get(xcs_core *core, char *buffer, size_t size, size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [buffer, size, length_r]{
    return CopyJson(CoreUnits::Describe(), buffer, size, length_r);
  });
}

xcs_status
xcs_units_set(xcs_core *core, uint32_t group, uint32_t unit)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [group, unit]{
    return CoreUnits::Set(group, unit) ? XCS_OK : XCS_ERROR_INVALID_ARGUMENT;
  });
}

xcs_status
xcs_units_preset(xcs_core *core, uint32_t index)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [index]{
    return CoreUnits::ApplyPreset(index) ? XCS_OK : XCS_ERROR_INVALID_ARGUMENT;
  });
}

xcs_status
xcs_sound_set_option(xcs_core *core, uint32_t option, int32_t value)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  /* like InputEvents::eventSounds() and eventAudioVolume() */
  return RunOnMain(*core, [option, value]{
    if (!AudioVarioGlue::HaveAudioVario())
      return XCS_ERROR_FAILED;

    auto &settings = CommonInterface::SetUISettings().sound.vario;
    switch (option) {
    case XCS_SOUND_VARIO:
      if (value != 0 && value != 1)
        return XCS_ERROR_INVALID_ARGUMENT;
      settings.enabled = value != 0;
      Profile::Set(ProfileKeys::SoundAudioVario, settings.enabled);
      break;

    case XCS_SOUND_VARIO_VOLUME:
      if (value < 0 || value > 100)
        return XCS_ERROR_INVALID_ARGUMENT;
      settings.volume = value;
      Profile::Set(ProfileKeys::SoundVolume, unsigned(settings.volume));
      break;

    default:
      return XCS_ERROR_INVALID_ARGUMENT;
    }

    AudioVarioGlue::Configure(settings);
    Profile::Save();
    return XCS_OK;
  });
}

xcs_status
xcs_sound_get_option(xcs_core *core, uint32_t option, int32_t *value_r)
{
  if (core == nullptr || value_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [option, value_r]{
    if (!AudioVarioGlue::HaveAudioVario())
      return XCS_ERROR_FAILED;

    const auto &settings = CommonInterface::GetUISettings().sound.vario;
    switch (option) {
    case XCS_SOUND_VARIO:
      *value_r = settings.enabled;
      return XCS_OK;

    case XCS_SOUND_VARIO_VOLUME:
      *value_r = settings.volume;
      return XCS_OK;

    default:
      return XCS_ERROR_INVALID_ARGUMENT;
    }
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
xcs_task_get(xcs_core *core, uint32_t which, char *buffer, size_t size,
             size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr ||
      (which != XCS_TASK_ACTIVE && which != XCS_TASK_EDITED))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [which, buffer, size, length_r]{
    const auto json = CoreTask::Describe(which == XCS_TASK_EDITED);
    if (json.empty())
      return XCS_ERROR_FAILED;
    return CopyJson(json, buffer, size, length_r);
  });
}

xcs_status
xcs_task_edit(xcs_core *core, uint32_t op, uint32_t index, double value)
{
  if (core == nullptr || std::isnan(value))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [op, index, value]{
    /* integer values: non-negative and exact */
    const bool integer = value >= 0 && value <= UINT32_MAX &&
      value == unsigned(value);
    bool ok;
    switch (op) {
    case XCS_TASK_BEGIN:
      CoreTask::BeginEdit();
      return XCS_OK;

    case XCS_TASK_CANCEL:
      CoreTask::CancelEdit();
      return XCS_OK;

    case XCS_TASK_COMMIT:
      return CoreTask::Commit() ? XCS_OK : XCS_ERROR_FAILED;

    case XCS_TASK_APPEND:
      ok = integer && CoreTask::Append(unsigned(value));
      break;

    case XCS_TASK_REMOVE:
      ok = CoreTask::Remove(index);
      break;

    case XCS_TASK_SWAP:
      ok = CoreTask::Swap(index);
      break;

    case XCS_TASK_CLEAR:
      ok = CoreTask::Clear();
      break;

    case XCS_TASK_SET_TYPE:
      ok = integer && CoreTask::SetType(unsigned(value));
      break;

    case XCS_TASK_SET_POINT_TYPE:
      ok = integer && CoreTask::SetPointType(index, unsigned(value));
      break;

    case XCS_TASK_SET_RADIUS:
      ok = CoreTask::SetRadius(index, value);
      break;

    case XCS_TASK_SET_AAT_MIN_TIME:
      ok = CoreTask::SetAATMinTime(value);
      break;

    case XCS_TASK_ADVANCE:
      ok = (value == 1 || value == -1) && CoreTask::Advance(int(value));
      break;

    case XCS_TASK_RESTART:
      CoreTask::Restart();
      return XCS_OK;

    default:
      ok = false;
    }

    return ok ? XCS_OK : XCS_ERROR_INVALID_ARGUMENT;
  });
}

xcs_status
xcs_task_list_files(xcs_core *core, char *buffer, size_t size,
                    size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [buffer, size, length_r]{
    return CopyJson(CoreTask::ListFiles(), buffer, size, length_r);
  });
}

xcs_status
xcs_task_load(xcs_core *core, const char *path, uint32_t index)
{
  if (core == nullptr || path == nullptr || *path == '\0' ||
      !ValidateUTF8(path))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [path, index]{
    return CoreTask::Load(path, index) ? XCS_OK : XCS_ERROR_FAILED;
  });
}

xcs_status
xcs_task_save(xcs_core *core, const char *name)
{
  if (core == nullptr || name == nullptr || *name == '\0' ||
      !ValidateUTF8(name) || std::strpbrk(name, "/\\") != nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [name]{
    return CoreTask::Save(name) ? XCS_OK : XCS_ERROR_FAILED;
  });
}

xcs_status
xcs_get_analysis(xcs_core *core, char *buffer, size_t size,
                 size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [buffer, size, length_r]{
    return CopyJson(CoreAnalysis::Describe(), buffer, size, length_r);
  });
}

/** A JSON getter on the core main thread. */
template<typename F>
static xcs_status
GetJsonOnMain(xcs_core *core, char *buffer, size_t size, size_t *length_r,
              F &&describe) noexcept
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [&]{
    return CopyJson(describe(), buffer, size, length_r);
  });
}

xcs_status
xcs_tiles_types(xcs_core *core, char *buffer, size_t size, size_t *length_r)
{
  return GetJsonOnMain(core, buffer, size, length_r,
                       CoreInfoBoxes::DescribeTypes);
}

xcs_status
xcs_tiles_layouts(xcs_core *core, char *buffer, size_t size,
                  size_t *length_r)
{
  return GetJsonOnMain(core, buffer, size, length_r,
                       CoreInfoBoxes::DescribeLayouts);
}

xcs_status
xcs_tiles_set(xcs_core *core, uint32_t layout, uint32_t tile, uint32_t type)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [layout, tile, type]{
    return CoreInfoBoxes::SetTile(layout, tile, type)
      ? XCS_OK : XCS_ERROR_INVALID_ARGUMENT;
  });
}

xcs_status
xcs_tiles_update(xcs_core *core, uint32_t layout, char *buffer, size_t size,
                 size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr ||
      layout >= CoreInfoBoxes::LAYOUT_COUNT)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [layout, buffer, size, length_r]{
    return CopyJson(CoreInfoBoxes::Update(layout), buffer, size, length_r);
  });
}

xcs_status
xcs_planes_list(xcs_core *core, char *buffer, size_t size, size_t *length_r)
{
  return GetJsonOnMain(core, buffer, size, length_r, CorePlanes::List);
}

xcs_status
xcs_polars_list(xcs_core *core, char *buffer, size_t size, size_t *length_r)
{
  return GetJsonOnMain(core, buffer, size, length_r,
                       CorePlanes::ListPolars);
}

xcs_status
xcs_plane_save(xcs_core *core, const char *path, const char *registration,
               const char *competition_id, const char *type, int32_t polar,
               uint32_t weglide_type, int double_seater,
               char *buffer, size_t size, size_t *length_r)
{
  if (core == nullptr || path == nullptr || registration == nullptr ||
      competition_id == nullptr || type == nullptr || buffer == nullptr ||
      length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [&]{
    const auto saved = CorePlanes::Save(path, registration, competition_id,
                                        type, polar, weglide_type,
                                        double_seater != 0);
    if (saved.empty())
      return XCS_ERROR_INVALID_ARGUMENT;
    return CopyJson(saved, buffer, size, length_r);
  });
}

xcs_status
xcs_plane_activate(xcs_core *core, const char *path)
{
  if (core == nullptr || path == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [path]{
    return CorePlanes::Activate(path) ? XCS_OK : XCS_ERROR_INVALID_ARGUMENT;
  });
}

xcs_status
xcs_plane_delete(xcs_core *core, const char *path)
{
  if (core == nullptr || path == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [path]{
    return CorePlanes::Delete(path) ? XCS_OK : XCS_ERROR_INVALID_ARGUMENT;
  });
}

xcs_status
xcs_crew_get(xcs_core *core, char *buffer, size_t size, size_t *length_r)
{
  return GetJsonOnMain(core, buffer, size, length_r,
                       CorePlanes::DescribeCrew);
}

xcs_status
xcs_crew_set(xcs_core *core, const char *pilot, const char *copilot)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [pilot, copilot]{
    return CorePlanes::SetCrew(pilot, copilot)
      ? XCS_OK : XCS_ERROR_INVALID_ARGUMENT;
  });
}

xcs_status
xcs_weglide_get(xcs_core *core, char *buffer, size_t size, size_t *length_r)
{
  return GetJsonOnMain(core, buffer, size, length_r,
                       CoreWeGlide::DescribeSettings);
}

xcs_status
xcs_weglide_set(xcs_core *core, int enabled, uint32_t pilot_id,
                const char *birthdate)
{
  if (core == nullptr || birthdate == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [enabled, pilot_id, birthdate]{
    return CoreWeGlide::SetSettings(enabled != 0, pilot_id, birthdate)
      ? XCS_OK : XCS_ERROR_INVALID_ARGUMENT;
  });
}

xcs_status
xcs_weglide_aircraft_search(xcs_core *core, const char *query, uint32_t max,
                            char *buffer, size_t size, size_t *length_r)
{
  if (query == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return GetJsonOnMain(core, buffer, size, length_r, [query, max]{
    return CoreWeGlide::SearchAircraft(query, max);
  });
}

/**
 * A blocking network call, on the caller's (worker) thread.  On error,
 * the buffer gets {"error": message}.
 */
template<typename F>
static xcs_status
RunNetwork(xcs_core *core, char *buffer, size_t size, size_t *length_r,
           F &&f) noexcept
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  if (!core->started || core->IsMainThread())
    return XCS_ERROR_STATE;

  try {
    return CopyJson(f(), buffer, size, length_r);
  } catch (...) {
    const auto message = GetFullMessage(std::current_exception());
    LogFmt("WeGlide: {}", message);
    StringOutputStream os;
    Json::Serialize(os, boost::json::object{{"error", message}});
    CopyJson(os.GetValue(), buffer, size, length_r);
    return XCS_ERROR_FAILED;
  }
}

xcs_status
xcs_weglide_aircraft_update(xcs_core *core, char *buffer, size_t size,
                            size_t *length_r)
{
  return RunNetwork(core, buffer, size, length_r, []{
    CoreWeGlide::UpdateAircraftList();
    return std::string{"{}"};
  });
}

xcs_status
xcs_weglide_aircraft_get(xcs_core *core, uint32_t id, char *buffer,
                         size_t size, size_t *length_r)
{
  if (id == 0)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunNetwork(core, buffer, size, length_r, [id]{
    return CoreWeGlide::DescribeAircraft(id);
  });
}

xcs_status
xcs_weglide_upload(xcs_core *core, const char *igc_path, char *buffer,
                   size_t size, size_t *length_r)
{
  if (igc_path == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  CoreWeGlide::UploadRequest request;
  bool configured = false;
  if (core != nullptr && !core->IsMainThread()) {
    const auto status = RunOnMain(*core, [&]{
      configured = CoreWeGlide::PrepareUpload(igc_path, request);
      return XCS_OK;
    });
    if (status != XCS_OK)
      return status;
  }

  return RunNetwork(core, buffer, size, length_r, [&]{
    if (!configured)
      throw std::runtime_error("Set your WeGlide pilot ID and date of "
                               "birth first");
    if (request.aircraft_id == 0)
      throw std::runtime_error("Choose the WeGlide aircraft type of "
                               "this plane first");
    return CoreWeGlide::Upload(igc_path, request);
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

xcs_status
xcs_map_items_at(xcs_core *core, int32_t x, int32_t y, char *buffer,
                 size_t size, size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [=]{
    const auto json = CoreMap::ItemsAt(x, y);
    *length_r = json.size();
    if (json.size() >= size)
      return XCS_ERROR_INVALID_ARGUMENT;

    std::memcpy(buffer, json.c_str(), json.size() + 1);
    return XCS_OK;
  });
#else
  (void)x;
  (void)y;
  (void)size;
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_map_set_option(xcs_core *core, uint32_t option, int32_t value)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [option, value]{
    return CoreMap::SetOption(option, value)
      ? XCS_OK
      : XCS_ERROR_INVALID_ARGUMENT;
  });
#else
  (void)option;
  (void)value;
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_map_get_option(xcs_core *core, uint32_t option, int32_t *value_r)
{
  if (core == nullptr || value_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

#ifdef ANDROID
  return RunOnMain(*core, [option, value_r]{
    int value;
    if (!CoreMap::GetOption(option, value))
      return XCS_ERROR_INVALID_ARGUMENT;
    *value_r = value;
    return XCS_OK;
  });
#else
  (void)option;
  return XCS_ERROR_FAILED;
#endif
}

xcs_status
xcs_goto_waypoint(xcs_core *core, uint32_t waypoint_id)
{
  if (core == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [waypoint_id]{
    auto &waypoints = *data_components->waypoints;
    auto waypoint = waypoints.LookupId(waypoint_id);
    if (waypoint == nullptr)
      return XCS_ERROR_INVALID_ARGUMENT;

    /* like MapItemListWidget::OnGotoClicked() */
    {
      const ScopeSuspendAllThreads suspend;
      waypoints.EraseTempGoto();
    }

    auto &task_manager = *backend_components->protected_task_manager;
    return task_manager.DoGoto(std::move(waypoint))
      ? XCS_OK
      : XCS_ERROR_FAILED;
  });
}

static const char *
RepositoryTypeName(FileType type) noexcept
{
  switch (type) {
  case FileType::MAP:
    return "map";
  case FileType::AIRSPACE:
    return "airspace";
  case FileType::WAYPOINT:
    return "waypoint";
  default:
    return "other";
  }
}

xcs_status
xcs_repository_list(const char *path, char *buffer, size_t size,
                    size_t *length_r)
{
  if (path == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  FileRepository repository;
  try {
    FileLineReaderA reader{Path{path}};
    ParseFileRepository(repository, reader);
  } catch (...) {
    /* no LogError(): this works without a core, and so without the
       data path the log file lives in */
    return XCS_ERROR_FAILED;
  }

  boost::json::array files;
  for (const auto &file : repository) {
    if (!file.IsValid())
      continue;

    boost::json::object o{
      {"name", file.name},
      {"uri", file.uri},
      {"type", RepositoryTypeName(file.type)},
      {"area", file.area.c_str()},
      {"description", file.description},
    };

    if (file.update_date.IsPlausible()) {
      char date[16];
      snprintf(date, sizeof(date), "%04u-%02u-%02u", file.update_date.year,
               file.update_date.month, file.update_date.day);
      o["updated"] = date;
    }

    if (const auto dir = GetFileTypeDefaultDir(file.type); dir != nullptr)
      o["folder"] = dir.c_str();

    if (file.HasHash()) {
      char hex[65];
      char *p = hex;
      for (auto b : file.sha256_hash)
        p = HexFormatUint8Fixed(p, uint8_t(b));
      *p = 0;
      o["sha256"] = hex;
    }

    files.emplace_back(std::move(o));
  }

  StringOutputStream os;
  Json::Serialize(os, files);
  const auto &json = os.GetValue();
  *length_r = json.size();
  if (json.size() >= size)
    return XCS_ERROR_INVALID_ARGUMENT;

  std::memcpy(buffer, json.c_str(), json.size() + 1);
  return XCS_OK;
}

/** An opaque id for an airspace, valid while it is loaded. */
static std::string
AirspaceId(const AbstractAirspace &airspace) noexcept
{
  char buffer[32];
  snprintf(buffer, sizeof(buffer), "%p", (const void *)&airspace);
  return buffer;
}

static const char *
WarningStateName(AirspaceWarning::State state) noexcept
{
  switch (state) {
  case AirspaceWarning::WARNING_INSIDE:
    return "inside";
  case AirspaceWarning::WARNING_GLIDE:
  case AirspaceWarning::WARNING_FILTER:
    return "near";
  case AirspaceWarning::WARNING_TASK:
    return "task";
  case AirspaceWarning::WARNING_CLEAR:
    break;
  }
  return "clear";
}

xcs_status
xcs_get_airspace_warnings(xcs_core *core, char *buffer, size_t size,
                          size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [buffer, size, length_r]{
    boost::json::array warnings;

    if (auto *manager = backend_components->GetAirspaceWarnings()) {
      const ProtectedAirspaceWarningManager::Lease lease{*manager};
      const AirspaceWarningManager &list = lease;
      /* most severe first, like the warning dialog; the
         acknowledgement itself, not IsActive(), which the calculation
         thread only updates on its next pass, so "Ack" shows at once */
      for (const auto &warning : list) {
        if (!warning.IsWarning() || !warning.IsAckExpired())
          continue;

        const auto &airspace = warning.GetAirspace();
        char top[64], base[64];
        AirspaceFormatter::FormatAltitudeShort(top, airspace.GetTop());
        AirspaceFormatter::FormatAltitudeShort(base, airspace.GetBase());

        boost::json::object o{
          {"id", AirspaceId(airspace)},
          {"state", WarningStateName(warning.GetWarningState())},
          {"name", airspace.GetName()},
          {"class", AirspaceFormatter::GetClassOrType(airspace)},
          {"top", top},
          {"base", base},
        };

        const auto &solution = warning.GetSolution();
        if (solution.IsValid()) {
          o["distance"] = solution.distance;
          o["time"] = solution.elapsed_time.count();
        }

        warnings.emplace_back(std::move(o));
      }
    }

    StringOutputStream os;
    Json::Serialize(os, warnings);
    const auto &json = os.GetValue();
    *length_r = json.size();
    if (json.size() >= size)
      return XCS_ERROR_INVALID_ARGUMENT;

    std::memcpy(buffer, json.c_str(), json.size() + 1);
    return XCS_OK;
  });
}

xcs_status
xcs_airspace_acknowledge(xcs_core *core, const char *id, uint32_t mode)
{
  if (core == nullptr || id == nullptr || mode > XCS_ACK_DAY)
    return XCS_ERROR_INVALID_ARGUMENT;

  const std::string wanted{id};
  return RunOnMain(*core, [wanted, mode]{
    auto *manager = backend_components->GetAirspaceWarnings();
    if (manager == nullptr)
      return XCS_ERROR_STATE;

    ConstAirspacePtr airspace;
    {
      const ProtectedAirspaceWarningManager::Lease lease{*manager};
      const AirspaceWarningManager &list = lease;
      for (const auto &warning : list)
        if (AirspaceId(warning.GetAirspace()) == wanted)
          airspace = warning.GetAirspacePtr();
    }

    if (!airspace)
      return XCS_ERROR_INVALID_ARGUMENT;

    /* like the buttons of XCSoar's airspace warning widget */
    if (mode == XCS_ACK_DAY)
      manager->AcknowledgeDay(std::move(airspace));
    else
      manager->Acknowledge(std::move(airspace));
    return XCS_OK;
  });
}

/** Profile keys of the app's airspace alert options, by option. */
static constexpr std::string_view
AirspaceAlertKey(uint32_t option) noexcept
{
  switch (option) {
  case XCS_AIRSPACE_ALERT_SOUND:
    return "MobileAirspaceSound";
  case XCS_AIRSPACE_ALERT_VIBRATION:
    return "MobileAirspaceVibration";
  case XCS_AIRSPACE_AUTO_HIDE:
    return "MobileAirspaceAutoHide";
  default:
    return {};
  }
}

static constexpr bool
IsAirspaceOption(uint32_t option) noexcept
{
  return option >= XCS_AIRSPACE_WARNINGS && option <= XCS_AIRSPACE_ACK_TIME;
}

static constexpr bool
IsValidAirspaceOption(uint32_t option, int32_t value) noexcept
{
  switch (option) {
  case XCS_AIRSPACE_WARNINGS:
  case XCS_AIRSPACE_ALERT_SOUND:
  case XCS_AIRSPACE_ALERT_VIBRATION:
    return value == 0 || value == 1;
  case XCS_AIRSPACE_AUTO_HIDE:
    return value >= 0 && value <= 600;
  case XCS_AIRSPACE_WARNING_TIME:
  case XCS_AIRSPACE_ACK_TIME:
    return value >= 10 && value <= 1000;
  default:
    return false;
  }
}

xcs_status
xcs_airspace_set_option(xcs_core *core, uint32_t option, int32_t value)
{
  if (core == nullptr || !IsValidAirspaceOption(option, value))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [option, value]{
    /* like AirspaceConfigPanel; the calculation thread gets the
       settings on the next timer tick (and clears the warnings when
       they are off) */
    auto &settings = CommonInterface::SetComputerSettings().airspace;
    const std::chrono::duration<unsigned> seconds(value);
    switch (option) {
    case XCS_AIRSPACE_WARNINGS:
      settings.enable_warnings = value != 0;
      Profile::Set(ProfileKeys::AirspaceWarning, settings.enable_warnings);
      break;

    case XCS_AIRSPACE_WARNING_TIME:
      settings.warnings.warning_time = seconds;
      Profile::Set(ProfileKeys::WarningTime, seconds);
      break;

    case XCS_AIRSPACE_ACK_TIME:
      settings.warnings.acknowledgement_time = seconds;
      Profile::Set(ProfileKeys::AcknowledgementTime, seconds);
      break;

    default:
      Profile::Set(AirspaceAlertKey(option), value);
    }

    Profile::Save();
    return XCS_OK;
  });
}

xcs_status
xcs_airspace_get_option(xcs_core *core, uint32_t option, int32_t *value_r)
{
  if (core == nullptr || value_r == nullptr || !IsAirspaceOption(option))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [option, value_r]{
    const auto &settings = CommonInterface::GetComputerSettings().airspace;
    switch (option) {
    case XCS_AIRSPACE_WARNINGS:
      *value_r = settings.enable_warnings;
      return XCS_OK;

    case XCS_AIRSPACE_WARNING_TIME:
      *value_r = settings.warnings.warning_time.count();
      return XCS_OK;

    case XCS_AIRSPACE_ACK_TIME:
      *value_r = settings.warnings.acknowledgement_time.count();
      return XCS_OK;
    }

    /* sound and vibration on, auto hide off until the pilot sets them */
    int value = option != XCS_AIRSPACE_AUTO_HIDE;
    Profile::Get(AirspaceAlertKey(option), value);
    *value_r = value;
    return XCS_OK;
  });
}

/** The range of a safety option; false for an unknown option. */
static constexpr bool
SafetyRange(uint32_t option, double &min, double &max) noexcept
{
  switch (option) {
  case XCS_SAFETY_ARRIVAL_HEIGHT:
    min = 0, max = 2000;
    return true;
  case XCS_SAFETY_TERRAIN_HEIGHT:
    min = 0, max = 1000;
    return true;
  case XCS_SAFETY_MC:
    min = 0, max = 10;
    return true;
  case XCS_SAFETY_RISK_FACTOR:
    min = 0, max = 1;
    return true;
  case XCS_SAFETY_ALTERNATES:
    min = 0, max = unsigned(AbortTaskMode::HOME);
    return true;
  case XCS_SAFETY_TURN_BACK_MARKER:
    min = 0, max = 1;
    return true;
  default:
    return false;
  }
}

xcs_status
xcs_safety_set_option(xcs_core *core, uint32_t option, double value)
{
  double min = 0, max = 0;
  if (core == nullptr || !SafetyRange(option, min, max) ||
      !(value >= min && value <= max))
    return XCS_ERROR_INVALID_ARGUMENT;

  /* like SafetyFactorsConfigPanel; TaskComputer hands the settings to
     the task manager on its next pass */
  return RunOnMain(*core, [option, value]{
    auto &task = CommonInterface::SetComputerSettings().task;
    switch (option) {
    case XCS_SAFETY_ARRIVAL_HEIGHT:
      task.safety_height_arrival = value;
      Profile::Set(ProfileKeys::SafetyAltitudeArrival, value);
      break;

    case XCS_SAFETY_TERRAIN_HEIGHT:
      task.route_planner.safety_height_terrain = value;
      Profile::Set(ProfileKeys::SafetyAltitudeTerrain, value);
      break;

    case XCS_SAFETY_MC:
      task.safety_mc = std::round(value * 10) / 10;
      Profile::Set(ProfileKeys::SafetyMacCready, iround(value * 10));
      break;

    case XCS_SAFETY_RISK_FACTOR:
      task.risk_gamma = std::round(value * 10) / 10;
      Profile::Set(ProfileKeys::RiskGamma, iround(value * 10));
      break;

    case XCS_SAFETY_ALTERNATES:
      task.abort_task_mode = AbortTaskMode(iround(value));
      Profile::SetEnum(ProfileKeys::AbortTaskMode, task.abort_task_mode);
      break;

    case XCS_SAFETY_TURN_BACK_MARKER:
      task.turn_back_marker_enabled = value != 0;
      Profile::Set(ProfileKeys::TurnBackMarkerEnabled,
                   task.turn_back_marker_enabled);
      break;
    }

    Profile::Save();
    return XCS_OK;
  });
}

xcs_status
xcs_safety_get_option(xcs_core *core, uint32_t option, double *value_r)
{
  double min = 0, max = 0;
  if (core == nullptr || value_r == nullptr || !SafetyRange(option, min, max))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [option, value_r]{
    const auto &task = CommonInterface::GetComputerSettings().task;
    switch (option) {
    case XCS_SAFETY_ARRIVAL_HEIGHT:
      *value_r = task.safety_height_arrival;
      break;
    case XCS_SAFETY_TERRAIN_HEIGHT:
      *value_r = task.route_planner.safety_height_terrain;
      break;
    case XCS_SAFETY_MC:
      *value_r = task.safety_mc;
      break;
    case XCS_SAFETY_RISK_FACTOR:
      *value_r = task.risk_gamma;
      break;
    case XCS_SAFETY_ALTERNATES:
      *value_r = unsigned(task.abort_task_mode);
      break;
    case XCS_SAFETY_TURN_BACK_MARKER:
      *value_r = task.turn_back_marker_enabled;
      break;
    }
    return XCS_OK;
  });
}

xcs_status
xcs_airspace_classes(xcs_core *core, char *buffer, size_t size,
                     size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr)
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [buffer, size, length_r]{
    /* counted like the warnings filter them: by class, or by type
       when the file gives no class (OpenAir "AY TMA" and the like) */
    std::array<unsigned, AIRSPACECLASSCOUNT> counts{};
    if (data_components != nullptr && data_components->airspaces != nullptr)
      for (const auto &i : data_components->airspaces->QueryAll())
        ++counts[unsigned(i.GetAirspace().GetClassOrType())];

    const auto &renderer = CommonInterface::GetMapSettings().airspace;
    const auto &warnings =
      CommonInterface::GetComputerSettings().airspace.warnings;
    boost::json::array classes;
    for (unsigned i = 0; i < AIRSPACECLASSCOUNT; ++i) {
      const char *name = AirspaceFormatter::GetClass(AirspaceClass(i));
      classes.emplace_back(boost::json::object{
        {"class", i},
        {"name", name != nullptr ? name : ""},
        {"display", renderer.classes[i].display},
        {"warning", warnings.class_warnings[i]},
        {"count", counts[i]},
      });
    }

    StringOutputStream os;
    Json::Serialize(os, classes);
    return CopyJson(os.GetValue(), buffer, size, length_r);
  });
}

xcs_status
xcs_airspace_set_class(xcs_core *core, uint32_t airspace_class,
                       int32_t display, int32_t warning)
{
  if (core == nullptr || airspace_class >= AIRSPACECLASSCOUNT ||
      (display != 0 && display != 1) || (warning != 0 && warning != 1))
    return XCS_ERROR_INVALID_ARGUMENT;

  return RunOnMain(*core, [airspace_class, display, warning]{
    /* like dlgAirspace; the map reads its settings on the next frame */
    CommonInterface::SetMapSettings().airspace.classes[airspace_class]
      .display = display != 0;
    CommonInterface::SetComputerSettings().airspace.warnings
      .class_warnings[airspace_class] = warning != 0;
    Profile::SetAirspaceMode(Profile::map, airspace_class,
                             display != 0, warning != 0);
    Profile::Save();
    return XCS_OK;
  });
}

xcs_status
xcs_waypoints_search(xcs_core *core, const char *name, uint32_t filter,
                     uint32_t max, char *buffer, size_t size,
                     size_t *length_r)
{
  if (core == nullptr || buffer == nullptr || length_r == nullptr ||
      filter > XCS_WAYPOINTS_AIRPORT ||
      (name != nullptr && !ValidateUTF8(name)))
    return XCS_ERROR_INVALID_ARGUMENT;

  const std::string wanted = name != nullptr ? name : "";
  return RunOnMain(*core, [=]{
    const auto &basic = CommonInterface::Basic();
    const auto &calculated = CommonInterface::Calculated();
    const auto &settings = CommonInterface::GetComputerSettings();
    const auto &waypoints = *data_components->waypoints;

    /* distances from the aircraft, else from home */
    GeoPoint location = GeoPoint::Invalid();
    if (basic.location_available)
      location = basic.location;
    else if (settings.poi.home_location_available)
      location = settings.poi.home_location;

    /* XCSoar's waypoint list, like the waypoint list dialog */
    WaypointFilter waypoint_filter;
    waypoint_filter.Clear();
    waypoint_filter.name = wanted.c_str();
    waypoint_filter.type_index = filter == XCS_WAYPOINTS_AIRPORT
      ? TypeFilter::AIRPORT
      : filter == XCS_WAYPOINTS_LANDABLE
      ? TypeFilter::LANDABLE
      : TypeFilter::ALL;

    WaypointList list;
    WaypointListBuilder builder(waypoint_filter,
                                location.IsValid() ? location
                                : GeoPoint::Zero(),
                                list, nullptr, 0);
    builder.Visit(waypoints);
    if (location.IsValid())
      list.SortByDistance(location);
    else
      list.SortByName();

    const auto *route_planner =
      &backend_components->glide_computer->GetProtectedRoutePlanner();

    boost::json::array result;
    for (const auto &item : list) {
      if (result.size() >= max)
        break;

      const auto &waypoint = *item.waypoint;
      boost::json::object o{
        {"id", waypoint.id},
        {"name", waypoint.name.c_str()},
        {"landable", waypoint.IsLandable()},
        {"airport", waypoint.IsAirport()},
      };
      if (waypoint.has_elevation)
        o["elevation"] = waypoint.elevation;

      if (location.IsValid()) {
        const auto &vector = item.GetVector(location);
        o["distance"] = vector.distance;
        o["bearing"] = vector.bearing.Degrees();
      }

      if (waypoint.IsLandable() && basic.location_available) {
        const auto reach =
          CalculateWaypointReach(waypoint, route_planner, basic, calculated,
                                 settings.polar, settings.task);
        if (reach.reachability != WaypointReachability::INVALID) {
          o["reachable"] = reach.IsReachable();
          o["arrival"] = reach.result.terrain_valid ==
            ReachResult::Validity::VALID
            ? reach.result.terrain
            : reach.result.direct;
        }
      }

      result.emplace_back(std::move(o));
    }

    StringOutputStream os;
    Json::Serialize(os, result);
    const auto &json = os.GetValue();
    *length_r = json.size();
    if (json.size() >= size)
      return XCS_ERROR_INVALID_ARGUMENT;

    std::memcpy(buffer, json.c_str(), json.size() + 1);
    return XCS_OK;
  });
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
