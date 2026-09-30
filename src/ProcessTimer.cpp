// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "ProcessTimer.hpp"
#include "BackendProcessTimer.hpp"
#include "Interface.hpp"
#include "ActionInterface.hpp"
#include "Input/InputEvents.hpp"
#include "Blackboard/DeviceBlackboard.hpp"
#include "time/RoughTime.hpp"
#include "MainWindow.hpp"
#include "PopupMessage.hpp"
#include "InfoBoxes/InfoBoxManager.hpp"
#include "ui/event/Idle.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"

#ifdef _WIN32
#include <windows.h>
#endif

#ifdef __APPLE__
#include "Apple/DarkMode.hpp"
#endif

static void
MessageProcessTimer() noexcept
{
  // don't display messages if airspace warning dialog is active
  if (CommonInterface::main_window->popup != nullptr &&
      CommonInterface::main_window->popup->Render())
    // turn screen on if blanked and receive a new message
    ResetUserIdle();
}

/**
 * Sets the system time to GPS time if not yet done and
 * defined in settings
 */
static void
SystemClockTimer() noexcept
{
#ifdef _WIN32
  const NMEAInfo &basic = CommonInterface::Basic();

  // as soon as we get a fix for the first time, set the
  // system clock to the GPS time.
  static bool sysTimeInitialised = false;

  if (basic.alive &&
      CommonInterface::GetComputerSettings().set_system_time_from_gps
      && basic.gps.real
      /* assume that we only have a valid date and time when we have a
         full GPS fix */
      && basic.location_available
      && !sysTimeInitialised) {
    SYSTEMTIME sysTime;
    ::GetSystemTime(&sysTime);

    sysTime.wYear = (unsigned short)basic.date_time_utc.year;
    sysTime.wMonth = (unsigned short)basic.date_time_utc.month;
    sysTime.wDay = (unsigned short)basic.date_time_utc.day;
    sysTime.wHour = (unsigned short)basic.date_time_utc.hour;
    sysTime.wMinute = (unsigned short)basic.date_time_utc.minute;
    sysTime.wSecond = (unsigned short)basic.date_time_utc.second;
    sysTime.wMilliseconds = 0;
    ::SetSystemTime(&sysTime);

    sysTimeInitialised =true;
  } else if (!basic.alive)
    /* set system clock again after a device reconnect; the new device
       may have a better GPS time */
    sysTimeInitialised = false;
#else
  // XXX
#endif
}

static void
SystemProcessTimer() noexcept
{
  SystemClockTimer();
}

static void
BlackboardProcessTimer() noexcept
{
  backend_components->device_blackboard->ExpireWallClock();
  XCSoarInterface::ExchangeBlackboard();
}

#ifdef __APPLE__

/**
 * Follows the operating system's appearance setting, which macOS and
 * iOS may switch at any time, e.g. on their sunset-to-sunrise
 * schedule.  Neither sends the application an event our event loop
 * could see, so the setting has to be polled; this is only necessary
 * while the user has asked us to follow it.
 */
static void
DarkModeProcessTimer() noexcept
{
  if (CommonInterface::GetUISettings().dark_mode !=
      UISettings::DarkMode::AUTO)
    return;

  if (UpdateAppleDarkMode())
    CommonInterface::main_window->ReinitialiseLook();
}

#endif

static void
CommonProcessTimer() noexcept
{
  BlackboardProcessTimer();

  BackendSettingsTimer();

#ifdef __APPLE__
  DarkModeProcessTimer();
#endif

  InfoBoxManager::ProcessTimer();
  InputEvents::ProcessTimer();

  MessageProcessTimer();
  SystemProcessTimer();
}

void
ProcessTimer() noexcept
{
  CommonProcessTimer();
  BackendDeviceTimer();
}
