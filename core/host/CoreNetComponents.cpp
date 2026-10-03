// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * The core's NetComponents (replaces src/NetComponents.cpp, a link-time
 * seam, mobile/docs/DECISIONS.md D5): live tracking, the thermal info
 * map and NOTAMs, which the backend timer and the merge thread feed.
 * The weather downloads (RASP, xctherm, EDL) are left out: their glue
 * reaches dialogs and the main window.
 */

#include "NetComponents.hpp"
#include "Tracking/TrackingGlue.hpp"
#include "net/client/tim/Glue.hpp"
#include "NOTAM/NOTAMGlue.hpp"
#include "Weather/Rasp/DownloadGlue.hpp"
#include "Weather/xctherm/XCThermDownloadGlue.hpp"
#ifdef HAVE_EDL
#include "Weather/EDL/DownloadGlue.hpp"
#endif

NetComponents::NetComponents(EventLoop &event_loop, CurlGlobal &curl,
                             const TrackingSettings &tracking_settings,
                             const NOTAMSettings &notam_settings)
  :tracking(new TrackingGlue(event_loop, curl)),
   tim(new TIM::Glue(curl)),
   notam(new NOTAMGlue(notam_settings, curl))
{
  tracking->SetSettings(tracking_settings);
}

NetComponents::~NetComponents() noexcept = default;

void
NetComponents::BeginShutdown() noexcept
{
  tracking->BeginShutdown();
  tim->BeginShutdown();
  notam->BeginShutdown();
}

/* The members the core never creates are always null, but their
   destructors are still referenced; defining them here (empty, never
   run) keeps the weather download code and the UI it reaches out of
   the link. */

RaspDownloadGlue::~RaspDownloadGlue() noexcept
{
}

void
XCThermDownloadGlue::BeginShutdown() noexcept
{
}

#ifdef HAVE_EDL
EDL::DownloadGlue::~DownloadGlue() noexcept
{
}
#endif
