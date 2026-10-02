// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * UI functions the map code reaches that have no meaning in the core
 * (link-time seams, mobile/docs/DECISIONS.md D5).  Map overlays from
 * weather services (SkySight) come later, through the app.
 */

#include "DataGlobals.hpp"
#include "UIGlobals.hpp"
#include "MainWindow.hpp"
#include "Weather/SkySight/SkySightClient.hpp"
#include "Look/DialogLook.hpp"

/* src/DataGlobals.cpp */
std::shared_ptr<SkySightClient>
DataGlobals::GetSkySight() noexcept
{
  return {};
}

/* src/UIGlobals.cpp: there is no GlueMapWindow in the core */
GlueMapWindow *
UIGlobals::GetMap()
{
  return nullptr;
}

GlueMapWindow *
UIGlobals::GetMapIfActive()
{
  return nullptr;
}

/* src/MainWindow.cpp: download progress widgets are not shown */
void
MainWindow::SetTopWidget([[maybe_unused]] Widget *widget) noexcept
{
}

/* src/UIGlobals.cpp: only reached through the progress widget above,
   which is never shown, so the look is never used */
const DialogLook &
UIGlobals::GetDialogLook()
{
  static const DialogLook unused{};
  return unused;
}
