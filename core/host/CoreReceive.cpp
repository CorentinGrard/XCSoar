// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Headless counterpart of src/UIReceiveBlackboard.cpp.  Keeps the
 * backend duties (blackboard exchange, device settings, device
 * notification, task events) and leaves out InfoBoxes, overlays and
 * the OpenVario system clock.  Keep in sync with that file.
 */

#include "CoreReceive.hpp"
#include "CoreListener.hpp"
#include "Interface.hpp"
#include "ActionInterface.hpp"
#include "ApplyVegaSwitches.hpp"
#include "ApplyExternalSettings.hpp"
#include "Device/MultipleDevices.hpp"
#include "Input/TaskEventObserver.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "Operation/PopupOperationEnvironment.hpp"
#include "ui/event/Notify.hpp"

#include <memory>

static TaskEventObserver task_event_observer;

void
CoreReceiveSensorData(OperationEnvironment &env) noexcept
{
  XCSoarInterface::ReceiveGPS();
  ApplyVegaSwitches();
  ApplyExternalSettings(env);
}

void
CoreReceiveCalculatedData() noexcept
{
  XCSoarInterface::ReceiveCalculated();

  if (backend_components->devices)
    backend_components->devices->NotifyCalculatedUpdate(CommonInterface::Basic(),
                                                        CommonInterface::Calculated());

  if (backend_components->protected_task_manager) {
    const ProtectedTaskManager::Lease lease{*backend_components->protected_task_manager};
    task_event_observer.Check(lease);
  }
}

static void
OnGPSNotify() noexcept
{
  PopupOperationEnvironment env;
  CoreReceiveSensorData(env);

  if (auto *listener = GetCoreListener())
    listener->OnGPSUpdate();
}

static void
OnCalculatedNotify() noexcept
{
  CoreReceiveCalculatedData();

  if (auto *listener = GetCoreListener())
    listener->OnCalculatedUpdate();
}

static std::unique_ptr<UI::Notify> gps_notify, calculated_notify;

void
CoreInitNotify() noexcept
{
  gps_notify = std::make_unique<UI::Notify>(OnGPSNotify);
  calculated_notify = std::make_unique<UI::Notify>(OnCalculatedNotify);
}

void
CoreDeinitNotify() noexcept
{
  gps_notify.reset();
  calculated_notify.reset();
}

void
CoreSendGPSNotify() noexcept
{
  if (gps_notify)
    gps_notify->SendNotification();
}

void
CoreSendCalculatedNotify() noexcept
{
  if (calculated_notify)
    calculated_notify->SendNotification();
}
