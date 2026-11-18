// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "ActionInterface.hpp"
#include "Interface.hpp"
#include "thread/Mutex.hxx"
#include "InfoBoxes/InfoBoxManager.hpp"
#include "FLARM/Glue.hpp"
#include "Device/MultipleDevices.hpp"
#include "Blackboard/DeviceBlackboard.hpp"
#include "CalculationThread.hpp"
#include "Task/TaskBehaviour.hpp"
#include "Profile/Profile.hpp"
#include "Operation/MessageOperationEnvironment.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "Observation/Publish.hpp"
#include "LogFile.hpp"
#include "Terrain/RasterTerrain.hpp"
#include "Session/SessionState.hpp"

using namespace CommonInterface;

static void
NoteSessionPolar() noexcept
{
  const auto &polar = GetComputerSettings().polar;
  Session::NotePolar(polar.glide_polar_task.GetBallastLitres(),
                     polar.glide_polar_task.GetCrewMass(),
                     polar.bugs);
}

void
XCSoarInterface::ReceiveGPS() noexcept
{
  {
    auto &device_blackboard = *backend_components->device_blackboard;
    const std::lock_guard lock{device_blackboard.mutex};

    ReadBlackboardBasic(device_blackboard.Basic());

    const NMEAInfo &real = device_blackboard.RealState();
    Private::movement_detected = real.alive && real.gps.real &&
      real.MovementDetected();
  }

  BroadcastGPSUpdate();

  if (!Basic().flarm.traffic.IsEmpty())
    /* auto-load FlarmNet when traffic is seen */
    LoadFlarmDatabases();
}

void
XCSoarInterface::ReceiveCalculated() noexcept
{
  {
    auto &device_blackboard = *backend_components->device_blackboard;
    const std::lock_guard lock{device_blackboard.mutex};

    ReadBlackboardCalculated(device_blackboard.Calculated());
    device_blackboard.ReadComputerSettings(GetComputerSettings());
  }

  if (data_components != nullptr && data_components->observations != nullptr)
    PublishOwnObservations(*data_components->observations, Calculated());

  BroadcastCalculatedUpdate();
}

void
XCSoarInterface::ExchangeDeviceBlackboard() noexcept
{
  auto &device_blackboard = *backend_components->device_blackboard;
  const std::lock_guard lock{device_blackboard.mutex};
  device_blackboard.ReadComputerSettings(GetComputerSettings());
}

void
ActionInterface::SetBallastLitres(double ballast_litres, bool to_devices) noexcept
{
  GlidePolar &polar = SetComputerSettings().polar.glide_polar_task;
  polar.SetBallastLitres(ballast_litres);

  if (backend_components)
    backend_components->SetTaskPolar(GetComputerSettings().polar);

  if (to_devices && backend_components && backend_components->devices) {
    const double ref_mass = polar.GetReferenceMass();
    if (ref_mass > 0) {
      MessageOperationEnvironment env;
      backend_components->devices->PutBallast(polar.GetBallastFraction(),
                                              polar.GetBallastOverload(),
                                              env);
    }
  }

  NoteSessionPolar();
}

void
ActionInterface::SetBallastFraction(double fraction, bool to_devices) noexcept
{
  GlidePolar &polar = SetComputerSettings().polar.glide_polar_task;
  polar.SetBallastFraction(fraction);

  if (backend_components)
    backend_components->SetTaskPolar(GetComputerSettings().polar);

  if (to_devices && backend_components && backend_components->devices) {
    const double ref_mass = polar.GetReferenceMass();
    if (ref_mass > 0) {
      MessageOperationEnvironment env;
      backend_components->devices->PutBallast(polar.GetBallastFraction(),
                                              polar.GetBallastOverload(),
                                              env);
    }
  }

  NoteSessionPolar();
}

bool
ActionInterface::SetBallastDump(bool active) noexcept
{
  ComputerSettings &settings = SetComputerSettings();

  if (active && (!settings.polar.glide_polar_task.HasBallast() ||
                 settings.plane.dump_time == 0))
    active = false;

  settings.polar.ballast_timer_active = active;
  return active;
}

void
ActionInterface::SetBugs(double bugs, bool to_devices) noexcept
{
  CommonInterface::SetComputerSettings().polar.SetBugs(bugs);

  if (backend_components)
    backend_components->SetTaskPolar(GetComputerSettings().polar);

  if (to_devices && backend_components && backend_components->devices) {
    MessageOperationEnvironment env;
    backend_components->devices->PutBugs(bugs, env);
  }

  NoteSessionPolar();
}

void
ActionInterface::SetCrewMass(double crew_mass, bool to_devices) noexcept
{
  GlidePolar &polar = SetComputerSettings().polar.glide_polar_task;
  polar.SetCrewMass(crew_mass);

  if (backend_components)
    backend_components->SetTaskPolar(GetComputerSettings().polar);

  if (to_devices && backend_components && backend_components->devices) {
    MessageOperationEnvironment env;
    backend_components->devices->PutCrewMass(crew_mass, env);
    /* The vario stores ballast as total mass over the reference
       mass.  A new crew weight changes that factor even when the
       water is unchanged, so send the factor again or the vario's
       litres drift by the crew change and the next LXWP2 copies
       the drift back here. */
    if (polar.GetReferenceMass() > 0)
      backend_components->devices->PutBallast(polar.GetBallastFraction(),
                                              polar.GetBallastOverload(),
                                              env);
  }

  NoteSessionPolar();
}

void
ActionInterface::SetEmptyMass(double empty_mass, bool to_devices) noexcept
{
  GlidePolar &polar = SetComputerSettings().polar.glide_polar_task;
  const double previous_empty = polar.GetEmptyMass();
  const bool empty_changed = previous_empty > empty_mass + 0.1 ||
                             previous_empty < empty_mass - 0.1;
  polar.SetEmptyMass(empty_mass);

  if (backend_components)
    backend_components->SetTaskPolar(GetComputerSettings().polar);

  if (to_devices && backend_components && backend_components->devices) {
    MessageOperationEnvironment env;
    backend_components->devices->PutEmptyMass(empty_mass, env);
    /* Overload is total mass over the reference mass.  Resend ballast
       only when the empty mass changed.  A save that leaves it alone
       must not push ballast. */
    if (empty_changed && polar.GetReferenceMass() > 0)
      backend_components->devices->PutBallast(polar.GetBallastFraction(),
                                              polar.GetBallastOverload(),
                                              env);
  }
}

void
ActionInterface::SetMaxTakeoffMass(double max_takeoff_mass,
                                  bool to_devices) noexcept
{
  SetComputerSettings().plane.max_takeoff_mass = max_takeoff_mass;

  if (!(to_devices && max_takeoff_mass > 0 &&
        backend_components && backend_components->devices))
    return;

  MessageOperationEnvironment env;
  backend_components->devices->PutMaxTakeoffMass(max_takeoff_mass, env);
}

void
ActionInterface::SetMacCready(double mc, bool to_devices) noexcept
{
  // Repeated adjustment of MC with the +/- UI elements could result in
  // an MC which is slightly larger than 0. Since the calculations
  // fundamentally change depending on  "MC == 0" or "MC <> 0" force
  // a 0 for small MC values.
  if (mc < 0.01)
    mc = 0;

  /* update interface settings */

  GlidePolar &polar = SetComputerSettings().polar.glide_polar_task;
  polar.SetMC(mc);

  /* update InfoBoxes (that might show the MacCready setting) */

  InfoBoxManager::SetDirty();

  /* send to calculation thread and trigger recalculation */
  if (backend_components)
    backend_components->SetTaskPolar(GetComputerSettings().polar);

  /* send to external devices */

  if (to_devices && backend_components && backend_components->devices) {
    MessageOperationEnvironment env;
    backend_components->devices->PutMacCready(mc, env);
  }
}

void ActionInterface::SetManualMacCready(double mc, bool to_devices) noexcept
{
  TaskBehaviour &task_behaviour = CommonInterface::SetComputerSettings().task;
  if (task_behaviour.auto_mc) {
    task_behaviour.auto_mc = false;
    Profile::Set(ProfileKeys::AutoMc, false);
  }

  SetMacCready(mc, to_devices);
}

void
ActionInterface::OffsetManualMacCready(double offset, bool to_devices) noexcept
{
  const GlidePolar &polar = GetComputerSettings().polar.glide_polar_task;
  const auto old_mc = polar.GetMC();
  auto mc = old_mc + offset;
  if (mc < 0)
    mc = 0;
  else if (mc > TaskBehaviour::TASK_MC_MAX)
    mc = TaskBehaviour::TASK_MC_MAX;

  if (mc != old_mc)
    SetManualMacCready(mc, to_devices);
}

static void
PutMacCreadyToDevices(double mc) noexcept
{
  if (!backend_components || !backend_components->devices)
    return;

  MessageOperationEnvironment env;
  backend_components->devices->PutMacCready(mc, env);
}

void
ActionInterface::OffsetSafetyMacCready(double offset) noexcept
{
  auto &settings = SetComputerSettings();
  const double task_mc = settings.polar.glide_polar_task.GetMC();
  const double old_safety = settings.task.safety_mc;

  auto mc = old_safety + offset;
  if (mc < 0)
    mc = 0;
  else if (mc > TaskBehaviour::SAFETY_MC_MAX)
    mc = TaskBehaviour::SAFETY_MC_MAX;

  if (mc == old_safety)
    return;

  settings.task.safety_mc = mc;

  if (backend_components && backend_components->calculation_thread)
    backend_components->calculation_thread->ForceTrigger();

  InfoBoxManager::SetDirty();

  /* Task MC above Safety MC is the faster command. Send the lower
     Safety MC. Once Safety MC is no longer below Task MC, put Task
     MC back. */
  const bool limits = task_mc > mc;
  const bool was_limiting = task_mc > old_safety;
  if (limits)
    PutMacCreadyToDevices(mc);
  else if (was_limiting)
    PutMacCreadyToDevices(task_mc);
}

void
ActionInterface::PublishAbortDeviceMacCready(bool in_abort) noexcept
{
  const auto &settings = GetComputerSettings();
  const double task_mc = settings.polar.glide_polar_task.GetMC();
  const double safety_mc = settings.task.safety_mc;
  if (!(task_mc > safety_mc))
    return;

  PutMacCreadyToDevices(in_abort ? safety_mc : task_mc);
}

void
ActionInterface::SetActiveFrequency(const RadioFrequency freq,
                                    const char *freq_name,
                                    bool to_devices) noexcept
{
  assert(freq.IsDefined());

  /* update interface settings */

  SetComputerSettings().radio.active_frequency = freq;
  if(freq_name != nullptr) {
    SetComputerSettings().radio.active_name = freq_name;
  }
  else {
    SetComputerSettings().radio.active_name.clear();
  }

  /* update InfoBoxes (that might show the ActiveFrequency setting) */

  InfoBoxManager::SetDirty();

  /* send to external devices */

  if (to_devices && backend_components && backend_components->devices) {
    MessageOperationEnvironment env;
    backend_components->devices->PutActiveFrequency(freq, freq_name, env);
  }
}

void
ActionInterface::SetStandbyFrequency(const RadioFrequency freq,
                                     const char *freq_name,
                                     bool to_devices) noexcept
{
  assert(freq.IsDefined());

  /* update interface settings */

  SetComputerSettings().radio.standby_frequency = freq;
  if(freq_name != nullptr) {
    SetComputerSettings().radio.standby_name = freq_name;
  }
  else {
    SetComputerSettings().radio.standby_name.clear();
  }

  /* update InfoBoxes (that might show the ActiveFrequency setting) */

  InfoBoxManager::SetDirty();

  /* send to external devices */

  if (to_devices && backend_components && backend_components->devices) {
    MessageOperationEnvironment env;
    backend_components->devices->PutStandbyFrequency(freq, freq_name, env);
  }
}

void
ActionInterface::OffsetActiveFrequency(double offset_khz,
                                       bool to_devices) noexcept
{
  RadioFrequency new_active_freq = SetComputerSettings().radio.active_frequency;
  if(new_active_freq.IsDefined()) {
    new_active_freq.OffsetKiloHertz(offset_khz);
    if(new_active_freq.IsDefined()) {
      ActionInterface::SetActiveFrequency(new_active_freq, nullptr, to_devices);
    }
  }
}

void
ActionInterface::OffsetStandbyFrequency(double offset_khz,
                                        bool to_devices) noexcept
{
  RadioFrequency new_standby_freq = SetComputerSettings().radio.standby_frequency;
  if(new_standby_freq.IsDefined()) {
    new_standby_freq.OffsetKiloHertz(offset_khz);
    if(new_standby_freq.IsDefined()) {
      ActionInterface::SetStandbyFrequency(new_standby_freq, nullptr, to_devices);
    }
  }
}

void
ActionInterface::ExchangeRadioFrequencies(bool to_devices) noexcept
{
  /* send to external devices */
  if (to_devices && backend_components && backend_components->devices) {
    MessageOperationEnvironment env;
    backend_components->devices->ExchangeRadioFrequencies(env);
  }
}

void
ActionInterface::SetTransponderCode(TransponderCode code,
                                    bool to_devices) noexcept
{
  assert(code.IsDefined());

  /* update interface settings */
  SetComputerSettings().transponder.transponder_code = code;

  /* update InfoBoxes (that might show the code setting) */
  InfoBoxManager::SetDirty();

  /* send to external devices */
  if (to_devices && backend_components && backend_components->devices) {
    MessageOperationEnvironment env;
    backend_components->devices->PutTransponderCode(code, env);
  }
}

void
ActionInterface::SetTransponderMode(TransponderMode mode) noexcept
{
  /* update interface settings */
  SetComputerSettings().transponder.transponder_mode = mode;

  /* update InfoBoxes (that might show the mode setting) */
  InfoBoxManager::SetDirty();

  /* Note: no device API currently exists to send only the mode. */
}

void
ActionInterface::SetStartupLocation() noexcept
{
  if (backend_components == nullptr ||
      backend_components->device_blackboard == nullptr)
    return;

  const auto &poi = GetComputerSettings().poi;
  if (poi.home_location_available) {
    LogString("Start at home waypoint");
    const double alt = poi.home_elevation_available
      ? poi.home_elevation
      : 0;
    backend_components->device_blackboard->SetStartupLocation(poi.home_location,
                                                              alt);
  } else if (data_components != nullptr &&
             data_components->terrain != nullptr) {
    const auto &terrain = *data_components->terrain;
    const GeoPoint loc = terrain.GetTerrainCenter();
    LogString("Start at terrain center");
    backend_components->device_blackboard->SetStartupLocation(
        loc, terrain.GetTerrainHeight(loc).GetValueOr0());
  }
}
