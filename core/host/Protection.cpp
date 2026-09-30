// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Headless replacement for src/Protection.cpp (link-time seam, see
 * mobile/docs/DECISIONS.md D5).  The backend-only functions are the
 * same as upstream; the ones that notified MainWindow notify the core
 * main thread instead (CoreReceive.cpp).  Keep in sync with
 * src/Protection.cpp.
 */

#include "Protection.hpp"
#include "CoreListener.hpp"
#include "CoreReceive.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "Computer/GlideComputer.hpp"
#include "CalculationThread.hpp"
#include "MergeThread.hpp"
#include "Blackboard/DeviceBlackboard.hpp"

#include <cassert>

bool global_running;

static CoreListener *core_listener;

void
SetCoreListener(CoreListener *listener) noexcept
{
  assert(!global_running);
  core_listener = listener;
}

CoreListener *
GetCoreListener() noexcept
{
  return core_listener;
}

void
TriggerMergeThread() noexcept
{
  if (backend_components->merge_thread)
    backend_components->merge_thread->Trigger();
}

void
TriggerGPSUpdate() noexcept
{
  if (backend_components->calculation_thread)
    backend_components->calculation_thread->Trigger();
}

void
ForceCalculation() noexcept
{
  if (backend_components->calculation_thread)
    backend_components->calculation_thread->ForceTrigger();
}

void
TriggerVarioUpdate([[maybe_unused]] bool vario_bar_redraw) noexcept
{
  /* like MainWindow::SendGPSUpdate(): continue on the main thread */
  CoreSendGPSNotify();
}

void
TriggerMapUpdate() noexcept
{
  /* no map yet (M3) */
}

void
TriggerCalculatedUpdate() noexcept
{
  /* like MainWindow::SendCalculatedUpdate() */
  CoreSendCalculatedNotify();
}

void
CreateCalculationThread() noexcept
{
  assert(backend_components->glide_computer != nullptr);

  auto &device_blackboard = *backend_components->device_blackboard;

  /* copy settings to DeviceBlackboard */
  device_blackboard.ReadComputerSettings(CommonInterface::GetComputerSettings());

  /* create and run MergeThread, because GlideComputer's first
     iteration depends on MergeThread's results */
  backend_components->merge_thread =
    std::make_unique<MergeThread>(*backend_components->device_blackboard,
                                  backend_components->devices.get(),
                                  &backend_components->glide_computer->GetTraceComputer());
  backend_components->merge_thread->FirstRun();

  /* copy the MergeThead::FirstRun() results to the
     InterfaceBlackboard because nothing else will initalise some
     important fallback values set by BasicComputer
     (e.g. AttitudeState::heading) */
  CommonInterface::ReadBlackboardBasic(device_blackboard.Basic());

  /* initialise the GlideComputer and run the first iteration */
  auto &glide_computer = *backend_components->glide_computer;
  glide_computer.ReadBlackboard(device_blackboard.Basic());
  glide_computer.ReadComputerSettings(device_blackboard.GetComputerSettings());
  glide_computer.ProcessGPS(true);

  /* copy GlideComputer results to DeviceBlackboard */
  device_blackboard.ReadBlackboard(glide_computer.Calculated());

  backend_components->calculation_thread =
    std::make_unique<CalculationThread>(device_blackboard, glide_computer);
  backend_components->calculation_thread->SetComputerSettings(CommonInterface::GetComputerSettings());
}

void
SuspendAllThreads() noexcept
{
  /* not suspending MergeThread, because it does not access shared
     unprotected data structures; there is no DrawThread here */

  if (backend_components->calculation_thread)
    backend_components->calculation_thread->Suspend();
}

void
ResumeAllThreads() noexcept
{
  if (backend_components->calculation_thread)
    backend_components->calculation_thread->Resume();
}
