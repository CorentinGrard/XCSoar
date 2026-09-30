// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "BackendProcessTimer.hpp"
#include "Interface.hpp"
#include "ActionInterface.hpp"
#include "Input/InputQueue.hpp"
#include "Device/MultipleDevices.hpp"
#include "Blackboard/DeviceBlackboard.hpp"
#include "time/PeriodClock.hpp"
#include "Simulator.hpp"
#include "Replay/Replay.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "BallastDumpManager.hpp"
#include "Operation/Operation.hpp"
#include "Tracking/TrackingGlue.hpp"
#include "net/client/tim/Glue.hpp"
#include "Components.hpp"
#include "NetComponents.hpp"
#include "BackendComponents.hpp"
#include "LogFile.hpp"

#ifdef HAVE_HTTP
#include "NOTAM/NOTAMGlue.hpp"
#endif

static void
BallastDumpProcessTimer() noexcept
{
  ComputerSettings &settings_computer =
    CommonInterface::SetComputerSettings();

  GlidePolar &glide_polar = settings_computer.polar.glide_polar_task;

  static BallastDumpManager ballast_manager;

  // Start/Stop the BallastDumpManager
  ballast_manager.SetEnabled(settings_computer.polar.ballast_timer_active);

  // If the BallastDumpManager is not enabled we must not call Update()
  if (!ballast_manager.IsEnabled())
    return;

  if (!ballast_manager.Update(glide_polar, settings_computer.plane.dump_time))
    // Plane is dry now -> disable ballast_timer
    settings_computer.polar.ballast_timer_active = false;

  if (backend_components->protected_task_manager != nullptr)
    backend_components->protected_task_manager->SetGlidePolar(glide_polar);
}

static void
ProcessAutoBugs() noexcept
{
  /**
   * Increase the bugs value every hour.
   */
  static constexpr FloatDuration interval = std::chrono::hours{1};

  /**
   * Decrement the bugs setting by 1%.
   */
  static constexpr double decrement(0.01);

  /**
   * Don't go below this bugs setting.
   */
  static constexpr double min_bugs(0.7);

  /**
   * The time stamp (from FlyingState::flight_time) when we last
   * increased the bugs value automatically.
   */
  static FloatDuration last_auto_bugs;

  const FlyingState &flight = CommonInterface::Calculated().flight;
  const PolarSettings &polar = CommonInterface::GetComputerSettings().polar;

  if (!flight.flying)
    /* reset when not flying */
    last_auto_bugs = {};
  else if (!polar.auto_bugs)
    /* feature is disabled */
    last_auto_bugs = flight.flight_time;
  else if (flight.flight_time >= last_auto_bugs + interval &&
           polar.bugs > min_bugs) {
    last_auto_bugs = flight.flight_time;
    ActionInterface::SetBugs(std::max(polar.bugs - decrement, min_bugs));
  }
}

/**
 * Keep the UTC offset up to date, unless the user has configured it
 * manually.  This picks up daylight saving time transitions, and time
 * zone changes while travelling.
 */
static void
UTCOffsetProcessTimer() noexcept
{
  const auto &settings = CommonInterface::GetComputerSettings();
  if (settings.local_time_source == LocalTimeSource::MANUAL_UTC_OFFSET)
    return;

  /* calculating the UTC offset is cheap, but there is no point in
     doing it on every timer tick */
  static PeriodClock clock;
  if (!clock.CheckUpdate(std::chrono::seconds(30)))
    return;

  if (const auto utc_offset = settings.GetCurrentUTCOffset();
      utc_offset != settings.utc_offset)
    CommonInterface::SetComputerSettings().utc_offset = utc_offset;
}

void
BackendSettingsTimer() noexcept
{
  UTCOffsetProcessTimer();
  BallastDumpProcessTimer();
  ProcessAutoBugs();
}

static void
ConnectionProcessTimer() noexcept
{
  if (backend_components->devices == nullptr)
    return;

  static bool connected_last = false;
  static bool location_last = false;
  static bool wait_connect = false;

  const NMEAInfo &basic = CommonInterface::Basic();

  const bool connected_now = basic.alive,
    location_now = basic.location_available;
  if (connected_now) {
    if (location_now) {
      wait_connect = false;
    } else if (!connected_last || location_last) {
      // waiting for lock first time
      InputEvents::processGlideComputer(GCE_GPS_FIX_WAIT);
    }
  } else if (!connected_last) {
    if (!wait_connect) {
      // gps is waiting for connection first time
      wait_connect = true;
      InputEvents::processGlideComputer(GCE_GPS_CONNECTION_WAIT);
    }
  }

  connected_last = connected_now;
  location_last = location_now;

  /* this OperationEnvironment instance must be persistent, because
     DeviceDescriptor::Open() is asynchronous */
  static QuietOperationEnvironment env;
  backend_components->devices->AutoReopen(env);
}

void
BackendDeviceTimer() noexcept
{
  if (!is_simulator()) {
    // now check GPS status
    if (backend_components->devices != nullptr)
      backend_components->devices->Tick();

    // also service replay logger
    if (backend_components->replay && backend_components->replay->IsActive()) {
      if (CommonInterface::MovementDetected())
        backend_components->replay->Stop();
    }

    ConnectionProcessTimer();
  } else {
    static PeriodClock m_clock;

    if (backend_components->replay && backend_components->replay->IsActive()) {
      m_clock.Update();
    } else if (m_clock.Elapsed() >= std::chrono::seconds(1)) {
      m_clock.Update();
      backend_components->device_blackboard->ProcessSimulation();
    } else if (!m_clock.IsDefined())
      m_clock.Update();
  }

  if (net_components != nullptr) {
#ifdef HAVE_TRACKING
    if (net_components->tracking) {
      net_components->tracking->SetSettings(CommonInterface::GetComputerSettings().tracking);
      net_components->tracking->OnTimer(CommonInterface::Basic(), CommonInterface::Calculated());
    }
#endif

#ifdef HAVE_HTTP
    if (net_components->tim != nullptr &&
        CommonInterface::GetComputerSettings().weather.enable_tim)
      net_components->tim->OnTimer(CommonInterface::Basic());

    const NMEAInfo &basic = CommonInterface::Basic();
    if (net_components->notam != nullptr) {
      const auto &notam_settings =
        CommonInterface::GetComputerSettings().airspace.notam;
      net_components->notam->SetSettings(notam_settings);
      if (notam_settings.enabled && basic.location_available) {
        const auto current_time_utc =
          basic.time_available && basic.date_time_utc.IsDatePlausible()
            ? basic.date_time_utc.ToTimePoint()
            : std::chrono::system_clock::now();
        try {
          net_components->notam->OnTimer(basic.location, current_time_utc);
        } catch (const std::exception &e) {
          LogFmt("NOTAM: OnTimer failed: {}", e.what());
        } catch (...) {
          LogFmt("NOTAM: OnTimer failed");
        }
      }
    }
#endif
  }
}
